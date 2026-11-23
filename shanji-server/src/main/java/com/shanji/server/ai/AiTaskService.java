package com.shanji.server.ai;

import com.shanji.server.common.BizException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Iterator;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * AI 生成任务中心（后台流式生成 + 断点续传）：
 * - POST /chat/task 创建任务后即在服务端线程池执行，与客户端连接无关（断开不取消）；
 * - GET /chat/stream/{id}?offset=N 以 SSE 输出：先补发 offset 之后的已有内容，再实时推送，
 *   任务完成发 done 事件后关闭连接；
 * - 任务完成后在内存保留 30 分钟，供断线/杀进程后凭 messageId + offset 续传；
 *   任务早已完成时，续传连接直接拿到完整内容。
 * 记录仍存客户端本地 Room（本项目不做多端同步）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiTaskService {

    /** 单个生成任务：缓冲 + 动作行 + 完成态（wait/notify 用任务自身作监视器） */
    public static class StreamTask {
        public final String id = UUID.randomUUID().toString().replace("-", "");
        public final StringBuilder content = new StringBuilder();
        public volatile String act;
        public volatile boolean done;
        public final long createdAt = System.currentTimeMillis();
    }

    /** 完成后保留时长：够杀进程后重新打开 App 续传 */
    private static final long DONE_TTL_MS = 30 * 60_000L;
    /** 兜底：超过该时长仍未完成视为失效（防止 GET 死等） */
    private static final long MAX_LIVE_MS = 5 * 60_000L;

    private final AiService aiService;
    private final Map<String, StreamTask> tasks = new ConcurrentHashMap<>();
    private final ExecutorService pool = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "ai-task");
        t.setDaemon(true);
        return t;
    });

    /** 创建任务并立即返回 messageId（生成在后台执行，不依赖客户端在线） */
    public String start(AiDtos.ChatRequest req) {
        if (req.messages() == null || req.messages().isEmpty()) {
            throw new BizException(org.springframework.http.HttpStatus.BAD_REQUEST, "说点什么吧");
        }
        purge();
        StreamTask t = new StreamTask();
        tasks.put(t.id, t);
        pool.submit(() -> run(t, req));
        log.info("ai task started: {}", t.id);
        return t.id;
    }

    public StreamTask get(String id) {
        purge();
        return tasks.get(id);
    }

    private void run(StreamTask t, AiDtos.ChatRequest req) {
        try {
            aiService.chatStream(
                req,
                delta -> { synchronized (t) { t.content.append(normalize(delta)); t.notifyAll(); } },
                act -> { synchronized (t) { t.act = normalize(act); t.notifyAll(); } }
            );
        } catch (Exception e) {
            // 失败也走完任务生命周期：人话错误作为正文补发，客户端照常收到 done
            String msg = (e instanceof BizException b) ? b.getMsg() : "AI 服务开了小差，稍后再试";
            synchronized (t) {
                if (t.content.length() > 0) t.content.append('\n');
                t.content.append(msg);
                t.notifyAll();
            }
            log.warn("ai task {} failed: {}", t.id, e.toString());
        } finally {
            synchronized (t) { t.done = true; t.notifyAll(); }
            log.info("ai task {} done, len={}", t.id, t.content.length());
        }
    }

    /** SSE 按行输出，\r 会让客户端 readLine 断行：统一归一成 \n */
    private static String normalize(String s) {
        return s.replace("\r\n", "\n").replace('\r', '\n');
    }

    private void purge() {
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<String, StreamTask>> it = tasks.entrySet().iterator();
        while (it.hasNext()) {
            StreamTask t = it.next().getValue();
            long age = now - t.createdAt;
            if (t.done ? age > DONE_TTL_MS : age > MAX_LIVE_MS) it.remove();
        }
    }
}
