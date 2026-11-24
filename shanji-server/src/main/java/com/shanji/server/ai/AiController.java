package com.shanji.server.ai;

import com.shanji.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@Slf4j
@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;
    private final AiTaskService taskService;

    /** 自然语言/语音记账解析 */
    @PostMapping("/parse")
    public ApiResponse<AiDtos.ParseResult> parse(@RequestBody AiDtos.ParseRequest req) {
        return ApiResponse.ok(aiService.parse(req));
    }

    /** 截图识别（多模态）：客户端压缩上传 base64，服务端持 key 调视觉模型 */
    @PostMapping("/vision")
    public ApiResponse<AiDtos.VisionResult> vision(@RequestBody AiDtos.VisionRequest req) {
        return ApiResponse.ok(aiService.vision(req));
    }

    /** 对话查账/记账（多轮由客户端维护） */
    @PostMapping("/chat")
    public ApiResponse<AiDtos.ChatResult> chat(@RequestBody AiDtos.ChatRequest req) {
        return ApiResponse.ok(aiService.chat(req));
    }

    /**
     * 启动后台生成任务，立即返回 messageId。
     * 生成在服务端线程池执行，客户端断开/切会话/杀进程都不取消（规则1）。
     */
    @PostMapping("/chat/task")
    public ApiResponse<String> startTask(@RequestBody AiDtos.ChatRequest req) {
        return ApiResponse.ok(taskService.start(req));
    }

    /**
     * SSE 断点续传读取：
     * - offset = 客户端已收到的正文字符数，服务端只补发缺失部分（规则5）；
     * - 任务已完成 → 补发剩余内容后直接发 done 关闭（"任务早已完成直接拉完整消息"）；
     * - 任务仍在生成 → 补发后挂起等待，增量实时推送；
     * - 写失败（客户端断开）只结束本连接，生成继续。
     * 协议：event: act|delta|done，data 行按 SSE 规范拼接；": ping" 为保活注释。
     * 同步写 HttpServletResponse（不走异步 dispatch，避免 Security ThreadLocal 丢失 → 403）。
     */
    @GetMapping(value = "/chat/stream/{id}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public void stream(@PathVariable String id,
                       @RequestParam(defaultValue = "0") long offset,
                       jakarta.servlet.http.HttpServletResponse resp) throws java.io.IOException {
        resp.setContentType("text/event-stream;charset=UTF-8");
        resp.setHeader("Cache-Control", "no-cache");
        java.io.OutputStream out = resp.getOutputStream();

        AiTaskService.StreamTask task = taskService.get(id);
        if (task == null) {
            // 任务不存在（过期/服务端重启）：明确告知客户端放弃续传
            write(out, "event: expired\ndata: 任务已失效\n\n");
            out.flush();
            return;
        }

        long sent = Math.max(0, offset);
        boolean actSent = offset > 0; // act 恒在正文之前下发：offset>0 说明客户端已拿到过
        try {
            while (true) {
                String act = null;
                String delta = null;
                boolean done;
                boolean waited = false;
                synchronized (task) {
                    // 内层循环：wait 醒来后就地重取，避免拿等待前的旧值多空转一轮
                    while (true) {
                        if (!actSent && task.act != null) act = task.act;
                        String c = task.content.toString();
                        if (c.length() > sent) {
                            delta = c.substring((int) sent);
                            sent = c.length();
                        }
                        done = task.done;
                        if (act != null || delta != null || done) break;
                        if (waited) break; // 超时醒来仍无数据 → 出去发 ping
                        waited = true;
                        task.wait(20_000); // 心跳周期：超时醒来发 ping，通知醒来有增量
                    }
                }
                if (act != null) {
                    writeData(out, "act", act);
                    actSent = true;
                }
                if (delta != null) writeData(out, "delta", delta);
                if (done) {
                    write(out, "event: done\n\n");
                    out.flush();
                    return;
                }
                if (act == null && delta == null) {
                    write(out, ": ping\n\n"); // 保活 + 探测死连接
                }
                out.flush();
            }
        } catch (java.io.IOException e) {
            // 客户端断开：任务继续生成，结果留在服务端等续传（规则1）
            log.info("ai stream client gone: {}", id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 洞察卡 / 月度报告文案 */
    @PostMapping("/insight")
    public ApiResponse<AiDtos.InsightResult> insight(@RequestBody AiDtos.InsightRequest req) {
        return ApiResponse.ok(aiService.insight(req));
    }

    /** 订阅识别（F7）：账单摘要 → AI 语义判断固定支出，客户端失败时才降级本地规则 */
    @PostMapping("/subscribe-scan")
    public ApiResponse<AiDtos.SubscribeScanResult> subscribeScan(@RequestBody AiDtos.SubscribeScanRequest req) {
        return ApiResponse.ok(aiService.subscribeScan(req));
    }

    private void write(java.io.OutputStream out, String s) throws java.io.IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
    }

    /** delta 可能含换行：按 SSE 规范拆成多行 data，客户端用 \n 拼回 */
    private void writeData(java.io.OutputStream out, String event, String data) throws java.io.IOException {
        StringBuilder sb = new StringBuilder("event: ").append(event).append('\n');
        String[] lines = data.split("\n", -1);
        for (String line : lines) sb.append("data: ").append(line).append('\n');
        sb.append('\n');
        out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
    }
}
