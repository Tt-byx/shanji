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

    public AiDtos.ParseResult parse(AiDtos.ParseRequest req) {
        String raw = req.rawText() == null ? "" : req.rawText().trim();
        if (raw.isEmpty() || raw.length() > 100) {
            throw new BizException(HttpStatus.BAD_REQUEST, "说一句要记的账，比如「午饭 15 块」");
        }
        String out = chat(PARSE_SYSTEM, raw, 0.1);
        JsonNode j = extractJson(out);
        if (j == null) {
            out = chat(PARSE_SYSTEM, raw + "（只输出 JSON）", 0.1); // 重试一次
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
        你能拿到的上下文（本月统计）会以 user 消息形式附上。
        只有需要查账或记账时才输出动作：第一行必须是以下格式（随后换行，第二行起是给用户的正文）：
        #ACT#{"actions":[{"fn":"queryTx","params":{"keyword":"可选","category":"可选","month":"yyyy-MM可选"}}]}
        记一笔用：#ACT#{"actions":[{"fn":"addTx","params":{"amount":数字,"merchant":"...","category":"key","type":"expense或income"}}]}
        不需要动作时禁止输出 #ACT# 行，直接输出正文。分类 key：dining/transport/shopping/daily/entertainment/health/education/other。
        禁止编造数字，统计里没有的就说没有。
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
        """;

    public AiDtos.InsightResult insight(AiDtos.InsightRequest req) {
        StringBuilder sb = new StringBuilder();
        if (req.persona() != null && req.persona().equals("savage")) {
            sb.append("语气：毒舌但善意。\n");
        }
        sb.append("[事件]\n");
        if (req.ruleHits() != null) {
            for (String h : req.ruleHits()) sb.append("- ").append(h).append('\n');
        }
        if (req.stats() != null && !req.stats().isBlank()) {
            sb.append("[统计]\n").append(req.stats()).append('\n');
        }
        String out = chat(INSIGHT_SYSTEM, sb.toString(), 0.6);
        // 抽正文，去围栏和 HTML 实体换行
        String t = out;
        if (t.startsWith("```")) t = t.replaceAll("^```[a-z]*\\n?|\\n?```$", "").trim();
        t = t.replace("&#10;", "\n").replace("&amp;", "&").trim();
        if (t.isBlank()) throw new BizException(HttpStatus.BAD_GATEWAY, "AI 没回话，稍后再试");
        return new AiDtos.InsightResult(t.length() > 500 ? t.substring(0, 500) : t);
    }
}
