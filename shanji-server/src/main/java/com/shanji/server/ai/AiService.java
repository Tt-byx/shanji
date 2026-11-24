package com.shanji.server.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shanji.server.common.BizException;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Semaphore;

/**
 * GLM 文本模型中转（服务器持有 apiKey，客户端不配 key）。
 * 内存敏感：连接超时 15s、max_tokens 限 800、信号量 2 并发。
 */
@Slf4j
@Service
public class AiService {

    private static final ObjectMapper M = new ObjectMapper();
    private static final Semaphore LIMIT = new Semaphore(2);

    @Value("${shanji.ai.apiKey:${GLM_API_KEY:}}")
    private String apiKey;

    @Value("${shanji.ai.baseUrl:https://open.bigmodel.cn/api/paas/v4}")
    private String baseUrl;

    @Value("${shanji.ai.model:glm-4-flash}")
    private String model;

    private RestClient client;

    @PostConstruct
    void init() {
        SimpleClientHttpRequestFactory f = new SimpleClientHttpRequestFactory();
        f.setConnectTimeout(15_000);
        f.setReadTimeout(60_000);
        this.client = RestClient.builder()
            .baseUrl(baseUrl)
            .requestFactory(f)
            .build();
    }

    private void ensureConfigured() {
        if (apiKey == null || apiKey.isBlank()) {
            throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "AI 还没配置好，先手动记账");
        }
    }

    /** 调 chat/completions，返回 content 原文；GLM 异常统一翻成人话 */
    private String chat(String system, String user, double temperature) {
        ensureConfigured();
        if (!LIMIT.tryAcquire()) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 忙不过来，稍后再试");
        }
        try {
            Map<String, Object> body = Map.of(
                "model", model,
                "temperature", temperature,
                // 推理模型的 reasoning 也计费计长，留足正文空间
                "max_tokens", 2000,
                "messages", List.of(
                    Map.of("role", "system", "content", system),
                    Map.of("role", "user", "content", user)
                )
            );
            String resp = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode root = M.readTree(resp);
            JsonNode content = root.path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new BizException(HttpStatus.BAD_GATEWAY, "AI 没回话，稍后再试");
            }
            return content.asText().trim();
        } catch (BizException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int sc = e.getStatusCode().value();
            if (sc == 401) throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "AI 密钥不对，联系管理员");
            if (sc == 429) throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 用得太频繁了，歇一会儿");
            log.warn("GLM http error: {} {}", sc, e.getResponseBodyAsString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "AI 服务开了小差，稍后再试");
        } catch (Exception e) {
            log.warn("GLM call failed: {}", e.toString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "连不上 AI 服务，稍后再试");
        } finally {
            LIMIT.release();
        }
    }

    /** 从模型回复里抠出 JSON（容忍 ```json 围栏和前后废话） */
    private JsonNode extractJson(String text) {
        String t = text.trim();
        int s = t.indexOf('{');
        int e = t.lastIndexOf('}');
        if (s < 0 || e <= s) return null;
        try {
            return M.readTree(t.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    /** 抠 JSON 数组（订阅识别这类只回数组的场景） */
    private JsonNode extractJsonArray(String text) {
        String t = text.trim();
        int s = t.indexOf('[');
        int e = t.lastIndexOf(']');
        if (s < 0 || e <= s) return null;
        try {
            return M.readTree(t.substring(s, e + 1));
        } catch (Exception ex) {
            return null;
        }
    }

    private static final String VISION_SYSTEM = """
        你是支付账单识别助手。从付款截图中提取信息，只返回一个 JSON 对象，格式：
        {"amount": 数字(支出金额，正数), "merchant": "商户或事项名称", "time": "yyyy-MM-dd HH:mm"(付款时间,未知则为 null)}
        不要返回任何其他文字、解释或 markdown 代码块标记。
        """;

    /** 截图识别：多模态 content（文本 + base64 图片），deepseek-flash 支持图片输入 */
    public AiDtos.VisionResult vision(AiDtos.VisionRequest req) {
        String img = req.image() == null ? "" : req.image().trim();
        if (img.isEmpty()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "没有收到图片");
        }
        if (img.length() > 2_000_000) { // ~1.5MB jpg 的 base64，超限拒收防打爆内存
            throw new BizException(HttpStatus.BAD_REQUEST, "图片太大，请截小一点");
        }
        ensureConfigured();
        if (!LIMIT.tryAcquire()) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 忙不过来，稍后再试");
        }
        try {
            Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.1,
                // 推理模型的 reasoning 也计费计长：300 会被 reasoning 吃光导致 content 为空
                "max_tokens", 1000,
                "messages", List.of(
                    Map.of("role", "system", "content", VISION_SYSTEM),
                    Map.of("role", "user", "content", List.of(
                        Map.of("type", "text", "text", "识别这张付款截图的金额、商户和付款时间"),
                        Map.of("type", "image_url", "image_url",
                            Map.of("url", "data:image/jpeg;base64," + img))
                    ))
                )
            );
            String resp = client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .retrieve()
                .body(String.class);
            JsonNode content = M.readTree(resp).path("choices").path(0).path("message").path("content");
            if (content.isMissingNode() || content.asText().isBlank()) {
                throw new BizException(HttpStatus.BAD_GATEWAY, "AI 没回话，稍后再试");
            }
            JsonNode j = extractJson(content.asText());
            if (j == null || !j.path("amount").isNumber()) {
                throw new BizException(HttpStatus.BAD_GATEWAY, "没认出金额，请手动填写");
            }
            double amount = j.path("amount").asDouble();
            if (amount <= 0 || amount > 1_000_000) {
                throw new BizException(HttpStatus.BAD_GATEWAY, "没认出金额，请手动填写");
            }
            String time = j.path("time").isTextual()
                && j.path("time").asText().matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}")
                ? j.path("time").asText() : null;
            return new AiDtos.VisionResult(amount, j.path("merchant").asText(""), time);
        } catch (BizException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int sc = e.getStatusCode().value();
            if (sc == 401) throw new BizException(HttpStatus.SERVICE_UNAVAILABLE, "AI 密钥不对，联系管理员");
            if (sc == 429) throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 用得太频繁了，歇一会儿");
            log.warn("GLM vision http error: {} {}", sc, e.getResponseBodyAsString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "AI 服务开了小差，稍后再试");
        } catch (Exception e) {
            log.warn("GLM vision failed: {}", e.toString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "连不上 AI 服务，稍后再试");
        } finally {
            LIMIT.release();
        }
    }

    private static final String PARSE_SYSTEM = """
        你是记账解析器。把用户的一句话消费解析成账单 JSON。只输出 JSON，禁止 markdown，禁止解释。
        格式：{"amount":数字(>0),"type":"expense或income","merchant":"商户或事项","category":"分类key","note":"备注，可空"}
        category 必须从下面 8 个 key 里选，按语义判断，不要输出 other，除非确实无法归类：
        dining=吃饭/外卖/奶茶/咖啡/食堂/晚饭/午饭/早饭/夜宵/餐厅
        transport=打车/地铁/公交/高铁/加油/共享单车/机票
        shopping=买衣服/数码/网购/淘宝/京东/超市买的商品
        daily=日用品/纸巾/洗衣液/理发/话费
        entertainment=电影/游戏/会员/演出/唱歌
        health=看病/药品/体检
        education=书/课程/学费/文具
        other=实在无法归类
        merchant 缺省时用动作场景（如「午饭」→「午饭」）。金额单位元，type 默认 expense。
        例子：输入「午饭 15 块」→ {"amount":15.0,"type":"expense","merchant":"午饭","category":"dining","note":""}
        输入「打车去机场 80」→ {"amount":80.0,"type":"expense","merchant":"打车","category":"transport","note":""}
        """;

    /** 把客户端本机学到的分类规则注入提示词：AI 侧也知道用户的历史习惯（越用越准） */
    private String buildParseSystem(AiDtos.ParseRequest req) {
        if (req.rules() == null || req.rules().isEmpty()) return PARSE_SYSTEM;
        StringBuilder sb = new StringBuilder(PARSE_SYSTEM);
        sb.append("\n用户已养成的习惯，命中时优先遵循，不要改成别的分类：\n");
        for (String r : req.rules()) {
            if (r != null && !r.isBlank()) sb.append("- ").append(r.trim()).append('\n');
        }
        return sb.toString();
    }

    public AiDtos.ParseResult parse(AiDtos.ParseRequest req) {
        String raw = req.rawText() == null ? "" : req.rawText().trim();
        if (raw.isEmpty() || raw.length() > 100) {
            throw new BizException(HttpStatus.BAD_REQUEST, "说一句要记的账，比如「午饭 15 块」");
        }
        String system = buildParseSystem(req);
        String out = chat(system, raw, 0.1);
        JsonNode j = extractJson(out);
        if (j == null) {
            out = chat(system, raw + "（只输出 JSON）", 0.1); // 重试一次
            j = extractJson(out);
        }
        if (j == null || !j.path("amount").isNumber()) {
            throw new BizException(HttpStatus.BAD_GATEWAY, "没听懂这句话，换个说法试试");
        }
        double amount = j.path("amount").asDouble();
        if (amount <= 0 || amount > 1_000_000) {
            throw new BizException(HttpStatus.BAD_GATEWAY, "没听懂金额，换个说法试试");
        }
        String type = j.path("type").asText("expense");
        if (!type.equals("income")) type = "expense";
        return new AiDtos.ParseResult(
            amount,
            type,
            j.path("merchant").asText(""),
            j.path("category").asText("other"),
            j.path("note").asText("")
        );
    }

    private static final String CHAT_SYSTEM = """
        你是记账 App「闪记」的理财小助手，用户是大学生。回答要短、口语化、直接给数字。
        你能拿到的上下文（近6个月统计、最近账单、预算、周期规则）会以 user 消息形式附上。
        只有需要动作时才输出动作：第一行必须是以下格式（随后换行，第二行起是给用户的正文）：
        #ACT#{"actions":[{"fn":"queryTx","params":{"keyword":"可选","category":"可选","month":"yyyy-MM可选"}}]}
        可用动作：
        - queryTx 查账：{"fn":"queryTx","params":{"keyword":"可选","category":"可选","month":"yyyy-MM可选"}}
        - addTx 记一笔：{"fn":"addTx","params":{"amount":数字,"merchant":"...","category":"key","type":"expense或income"}}
        - trend 趋势对比：{"fn":"trend","params":{"months":6,"category":"可选"}}
        - budget_set 设预算：{"fn":"budget_set","params":{"scope":"total或category","category":"可选","amount":数字}}
        - subscribe 识别订阅类固定支出：{"fn":"subscribe","params":{}}
        不需要动作时禁止输出 #ACT# 行，直接输出正文。分类 key：dining/transport/shopping/daily/entertainment/health/education/other。
        硬性规则：
        - 正文和卡片里的每个数字都必须来自 [上下文]/[对话历史] 或动作返回结果；账本里没有的数字一律不得出现，没有的就说没有，禁止估算或推算。
        - 订阅 / 周期性固定支出的结论必须来自 subscribe 动作的返回结果，禁止自行推断；动作没返回订阅就说明没有。
        给出分类统计结论且有可操作建议时，在 #ACT# JSON 里额外附带 "card" 字段（用户会看到卡片并可点按钮）：
        #ACT#{"actions":[],"card":{"title":"购物 ¥{金额} · 占{百分比}% · {笔数}笔","lines":["比上月省 ¥{金额}"],"buttons":[{"label":"看看这{笔数}笔","fn":"queryTx","params":{"category":"shopping"}},{"label":"设个¥{金额}预算","fn":"budget_set","params":{"scope":"category","category":"shopping","amount":{金额}}}]}}
        示例里的 {金额}/{百分比}/{笔数} 全是占位符，输出时必须替换成上下文里的真实数字，禁止照抄占位符或示例值。
        card 的 buttons 里 fn 只能用 queryTx / budget_set / addTx；没有合适按钮就省略 card。
        正文引用具体数字时，在数字后面紧跟溯源链接，用户点击可查看对应账单：
        [¥{金额}](shanji-trace:category=dining&month={yyyy-MM})
        链接里的金额用正文实际数字，category/month 从上下文取；上下文里没有的数据不要加链接。
        """;

    public AiDtos.ChatResult chat(AiDtos.ChatRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "说点什么吧");
        }
        String out = chat(buildChatSystem(req.persona()), buildChatUser(req), 0.4);
        // 兼容 #ACT# 首行协议
        String reply = out;
        java.util.ArrayList<AiDtos.Action> actions = new java.util.ArrayList<>();
        int nl = out.indexOf('\n');
        String head = (nl >= 0 ? out.substring(0, nl) : out).trim();
        if (head.startsWith("#ACT#")) {
            JsonNode j = extractJson(head.substring(5));
            if (j != null) {
                for (JsonNode a : j.path("actions")) {
                    if (!a.path("fn").asText("").isBlank()) {
                        actions.add(new AiDtos.Action(
                            a.path("fn").asText(),
                            M.convertValue(a.path("params"), M.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                        ));
                    }
                }
                reply = nl >= 0 ? out.substring(nl + 1).trim() : "";
            }
        } else {
            JsonNode j = extractJson(out);
            if (j != null && j.has("reply")) {
                return toChatResult(j, out); // 旧 JSON 协议兜底
            }
        }
        return new AiDtos.ChatResult(reply, actions);
    }

    /** 流式对话：正文逐段回调；需动作时模型首行输出 #ACT#JSON，服务端吞掉该行改走 onAct */
    public void chatStream(
        AiDtos.ChatRequest req,
        java.util.function.Consumer<String> onDelta,
        java.util.function.Consumer<String> onAct
    ) {
        if (req.messages() == null || req.messages().isEmpty()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "说点什么吧");
        }
        ensureConfigured();
        if (!LIMIT.tryAcquire()) {
            throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 忙不过来，稍后再试");
        }
        try {
            Map<String, Object> body = Map.of(
                "model", model,
                "temperature", 0.4,
                "max_tokens", 2000,
                "stream", true,
                "messages", List.of(
                    Map.of("role", "system", "content", buildChatSystem(req.persona())),
                    Map.of("role", "user", "content", buildChatUser(req))
                )
            );
            final StringBuilder actBuf = new StringBuilder();
            final boolean[] actDone = {false};
            client.post()
                .uri("/chat/completions")
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .body(body)
                .exchange((request, response) -> {
                    try (java.io.BufferedReader r = new java.io.BufferedReader(
                            new java.io.InputStreamReader(response.getBody(), java.nio.charset.StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = r.readLine()) != null) {
                            if (!line.startsWith("data:")) continue;
                            String data = line.substring(5).trim();
                            if (data.isEmpty() || data.equals("[DONE]")) continue;
                            JsonNode n;
                            try { n = M.readTree(data); } catch (Exception e) { continue; }
                            String delta = n.path("choices").path(0).path("delta").path("content").asText("");
                            if (delta.isEmpty()) continue;
                            // DeepSeek 偶发把换行转成字面 HTML 实体
                            delta = delta.replace("&#10;", "\n").replace("&amp;", "&");
                            if (actDone[0]) {
                                onDelta.accept(delta);
                                continue;
                            }
                            // 首行还在缓冲：判断是不是 #ACT# 动作行
                            actBuf.append(delta);
                            int nl = actBuf.indexOf("\n");
                            if (nl >= 0) {
                                String first = actBuf.substring(0, nl).trim();
                                actDone[0] = true;
                                if (first.startsWith("#ACT#")) {
                                    onAct.accept(first.substring(5));
                                } else if (!first.isEmpty()) {
                                    onDelta.accept(actBuf.substring(0, nl));
                                }
                                String rest = actBuf.substring(nl + 1);
                                if (!rest.isEmpty()) onDelta.accept(rest);
                            } else if (actBuf.length() > 4096) {
                                // 超长无换行：不是动作行，直接放行
                                actDone[0] = true;
                                onDelta.accept(actBuf.toString());
                            }
                        }
                        if (!actDone[0] && actBuf.length() > 0) {
                            onDelta.accept(actBuf.toString());
                        }
                    }
                    return null;
                });
        } catch (BizException e) {
            throw e;
        } catch (org.springframework.web.client.RestClientResponseException e) {
            int sc = e.getStatusCode().value();
            if (sc == 429) throw new BizException(HttpStatus.TOO_MANY_REQUESTS, "AI 用得太频繁了，歇一会儿");
            log.warn("GLM stream http error: {} {}", sc, e.getResponseBodyAsString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "AI 服务开了小差，稍后再试");
        } catch (Exception e) {
            log.warn("GLM stream failed: {}", e.toString());
            throw new BizException(HttpStatus.BAD_GATEWAY, "连不上 AI 服务，稍后再试");
        } finally {
            LIMIT.release();
        }
    }

    private String buildChatSystem(String persona) {
        String system = CHAT_SYSTEM;
        if ("savage".equals(persona)) {
            system = "你说话毒舌但善意，会吐槽用户乱花钱，像 Cleo，最后还是给建议。\n" + system;
        } else if ("gentle".equals(persona)) {
            system = "你温和鼓励，先肯定用户做得好的地方再给建议。\n" + system;
        }
        return system;
    }

    private String buildChatUser(AiDtos.ChatRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.context() != null && !req.context().isBlank()) {
            sb.append("[上下文]\n").append(req.context()).append("\n\n");
        }
        sb.append("[对话历史]\n");
        for (AiDtos.Message m : req.messages()) {
            sb.append(m.role()).append(": ").append(m.content()).append('\n');
        }
        return sb.toString();
    }

    private AiDtos.ChatResult toChatResult(JsonNode j, String fallback) {
        String reply = j.path("reply").asText(fallback);
        java.util.ArrayList<AiDtos.Action> actions = new java.util.ArrayList<>();
        for (JsonNode a : j.path("actions")) {
            if (!a.path("fn").asText("").isBlank()) {
                actions.add(new AiDtos.Action(
                    a.path("fn").asText(),
                    M.convertValue(a.path("params"), M.getTypeFactory().constructMapType(Map.class, String.class, Object.class))
                ));
            }
        }
        return new AiDtos.ChatResult(reply, actions);
    }

    private static final String INSIGHT_SYSTEM = """
        你是记账 App「闪记」的理财小助手，用户是大学生。
        根据给定的规则命中事件和统计数据，生成简短人话洞察。只输出正文，禁止 markdown、禁止标题。
        要求：一句话一个要点，最多 3 条，每条不超过 40 字，带具体数字，给一条可执行的小建议。
        数字只能来自给定的 [事件]/[统计]，账本里没有的数字不得出现，禁止估算或编造。
        """;

    /** 统计页洞察卡（F22）：允许溯源链接 + 末尾可选 #CARD# 操作卡片；通知类任务仍走上面的纯文本版 */
    private static final String INSIGHT_CARD_SYSTEM = """
        你是记账 App「闪记」的理财小助手，用户是大学生。
        根据给定的规则命中事件和统计数据，生成简短人话洞察。
        要求：一句话一个要点，最多 3 条，每条不超过 45 字，带具体数字，给一条可执行的小建议。禁止标题。
        数字只能来自给定的 [事件]/[统计]，账本里没有的数字不得出现，禁止估算或编造。
        引用 [统计] 里的分类金额时，在数字后面紧跟溯源链接（用户点击可查看对应账单明细）：
        [¥{金额}](shanji-trace:category={分类key}&month={yyyy-MM})
        month 用 [统计] 里给出的月份，category 用 [统计] 括号里的分类 key（如 dining）；[统计] 里没有的数字不要加链接。
        最后可单独起一行输出一张操作卡片（没有合适按钮就整行省略，卡片必须放正文末尾）：
        #CARD#{"title":"{带真实数字的结论}","lines":["{一条补充}"],"buttons":[{"label":"{按钮文字}","fn":"queryTx","params":{"category":"{分类key}","month":"{yyyy-MM}"}}]}
        buttons 的 fn 只能是 queryTx / budget_set / addTx；params 只用 category/month/keyword/scope/amount；
        卡片里的数字同样必须来自 [事件]/[统计]，占位符 {xxx} 必须替换成真实值，禁止照抄示例。
        """;

    public AiDtos.InsightResult insight(AiDtos.InsightRequest req) {
        boolean statCard = "insight".equals(req.kind());
        StringBuilder sb = new StringBuilder();
        if (req.persona() != null && req.persona().equals("savage")) {
            sb.append("语气：毒舌但善意。\n");
        }
        // 主动式推送的两种任务：明确告诉模型要产出什么，否则它只会泛泛复述统计
        String task = null;
        if ("weekly_report".equals(req.kind())) {
            task = "这是每周一早上推给用户的周报，回顾上周（周一到周日，不是刚开始的本周），写 3 条以内："
                + "上周花了多少、比前一周多还是少、上周哪一类花得最多，每条带具体数字，最后一句给个可执行的小建议。";
        } else if ("budget_over".equals(req.kind())) {
            task = "用户的预算刚被超出，写一条 20 字以内、能立刻执行的省钱建议，带具体数字，不要说教。";
        }
        if (task != null) sb.append("[任务]\n").append(task).append('\n');
        sb.append("[事件]\n");
        if (req.ruleHits() != null) {
            for (String h : req.ruleHits()) sb.append("- ").append(h).append('\n');
        }
        if (req.stats() != null && !req.stats().isBlank()) {
            sb.append("[统计]\n").append(req.stats()).append('\n');
        }
        String out = chat(statCard ? INSIGHT_CARD_SYSTEM : INSIGHT_SYSTEM, sb.toString(), 0.6);
        // 抽正文，去围栏和 HTML 实体换行
        String t = out;
        if (t.startsWith("```")) t = t.replaceAll("^```[a-z]*\\n?|\\n?```$", "").trim();
        t = t.replace("&#10;", "\n").replace("&amp;", "&").trim();
        if (t.isBlank()) throw new BizException(HttpStatus.BAD_GATEWAY, "AI 没回话，稍后再试");
        // 洞察卡带链接和 #CARD# 行，给足余量；通知类仍是纯短文案
        int limit = statCard ? 1500 : 500;
        return new AiDtos.InsightResult(t.length() > limit ? t.substring(0, limit) : t);
    }

    private static final String SUBSCRIBE_SCAN_SYSTEM = """
        你是记账 App「闪记」的订阅识别器。输入是用户近几个月的账单流水，每行格式：
        yyyy-MM-dd|商户名|金额|分类key
        识别「订阅 / 固定支出」：同一商户连续 3 个月以上每月都出现、且金额基本稳定的支出，
        如视频会员、话费、云盘、健身卡、房租。
        只输出一个 JSON 数组，不要解释、不要 markdown 围栏、不要代码块：
        [{"name":"商户名","amount":月均金额(数字),"category":"分类key","cycle":"monthly","dayOfMonth":15,"dueMonth":0}]
        规则：
        - 不满足「连续每月出现且金额稳定」的不要输出；不确定的宁可不输出；没有就返回 []
        - cycle 固定填 monthly；dayOfMonth 取最近一笔账单的日期几号（1~31）
        - category 沿用账单行里的分类 key（dining/transport/shopping/daily/entertainment/health/education/other），不要改
        - [已识别，跳过] 里列出的商户已存在，直接跳过
        - 结果最多 30 条
        """;

    /** 订阅识别（F7）：语义判断交给 AI，客户端在调用失败时才降级本地规则 */
    public AiDtos.SubscribeScanResult subscribeScan(AiDtos.SubscribeScanRequest req) {
        String summary = req.txSummary() == null ? "" : req.txSummary().trim();
        if (summary.isEmpty()) {
            throw new BizException(HttpStatus.BAD_REQUEST, "没有可分析的账单");
        }
        if (summary.length() > 60_000) summary = summary.substring(0, 60_000);
        StringBuilder sb = new StringBuilder("[账单流水]\n").append(summary).append('\n');
        if (req.existing() != null && !req.existing().isEmpty()) {
            sb.append("[已识别，跳过]\n").append(String.join("、", req.existing())).append('\n');
        }
        String out = chat(SUBSCRIBE_SCAN_SYSTEM, sb.toString(), 0.2);
        JsonNode arr = extractJsonArray(out);
        java.util.ArrayList<AiDtos.SubscribeScanItem> items = new java.util.ArrayList<>();
        if (arr != null && arr.isArray()) {
            java.util.Set<String> cats = java.util.Set.of(
                "dining", "transport", "shopping", "daily", "entertainment", "health", "education", "other");
            for (JsonNode n : arr) {
                if (items.size() >= 30) break;
                String name = n.path("name").asText("").trim();
                double amount = n.path("amount").asDouble(0);
                if (name.isEmpty() || name.length() > 30 || amount <= 0 || amount > 1_000_000) continue;
                String cat = n.path("category").asText("other");
                if (!cats.contains(cat)) cat = "other";
                String cycle = n.path("cycle").asText("monthly");
                if (!cycle.equals("yearly")) cycle = "monthly";
                int day = n.path("dayOfMonth").asInt(1);
                if (day < 1 || day > 31) day = 1;
                int dueMonth = n.path("dueMonth").asInt(0);
                if (dueMonth < 0 || dueMonth > 12) dueMonth = 0;
                items.add(new AiDtos.SubscribeScanItem(name, amount, cat, cycle, day, dueMonth));
            }
        }
        return new AiDtos.SubscribeScanResult(items);
    }
}
