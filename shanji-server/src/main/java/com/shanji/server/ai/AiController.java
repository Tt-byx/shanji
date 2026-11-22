package com.shanji.server.ai;

import com.shanji.server.common.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;

@RestController
@RequestMapping("/api/ai")
@RequiredArgsConstructor
public class AiController {

    private final AiService aiService;

    /** 自然语言/语音记账解析 */
    @PostMapping("/parse")
    public ApiResponse<AiDtos.ParseResult> parse(@RequestBody AiDtos.ParseRequest req) {
        return ApiResponse.ok(aiService.parse(req));
    }

    /** 对话查账/记账（多轮由客户端维护） */
    @PostMapping("/chat")
    public ApiResponse<AiDtos.ChatResult> chat(@RequestBody AiDtos.ChatRequest req) {
        return ApiResponse.ok(aiService.chat(req));
    }

    /**
     * 流式对话：chunked text/plain，逐段返回正文。
     * 动作行原样透传（首行 #ACT#JSON），客户端解析后执行。
     * 注意：同步写 HttpServletResponse（不走 StreamingResponseBody 异步，
     * 否则 async dispatch 时 Spring Security 的 ThreadLocal 上下文丢失 → 403）。
     */
    @PostMapping(value = "/chat/stream", produces = MediaType.TEXT_PLAIN_VALUE)
    public void chatStream(@RequestBody AiDtos.ChatRequest req, jakarta.servlet.http.HttpServletResponse resp)
            throws java.io.IOException {
        resp.setContentType("text/plain;charset=UTF-8");
        resp.setStatus(200);
        java.io.OutputStream out = resp.getOutputStream();
        try {
            aiService.chatStream(
                req,
                delta -> write(out, delta),
                act -> write(out, "#ACT#" + act + "\n")
            );
        } catch (Exception e) {
            // 流已开始无法改状态码：把人话错误作为一段正文补发
            String msg = (e instanceof com.shanji.server.common.BizException b) ? b.getMsg() : "AI 服务开了小差，稍后再试";
            write(out, "\n" + msg);
        }
        out.flush();
    }

    private void write(java.io.OutputStream out, String s) {
        try {
            out.write(s.getBytes(StandardCharsets.UTF_8));
            out.flush();
        } catch (java.io.IOException ignored) {
        }
    }

    /** 洞察卡 / 月度报告文案 */
    @PostMapping("/insight")
    public ApiResponse<AiDtos.InsightResult> insight(@RequestBody AiDtos.InsightRequest req) {
        return ApiResponse.ok(aiService.insight(req));
    }
}
