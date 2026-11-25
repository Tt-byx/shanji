package com.shanji.server.ai;

import java.util.List;
import java.util.Map;

/** AI 接口请求/响应 DTO */
public class AiDtos {

    /** 自然语言记账解析。rules = 客户端本机学到的分类规则摘要（商户+金额+时段 → 分类） */
    public record ParseRequest(String rawText, String persona, List<String> rules) {}

    public record ParseResult(
        double amount,
        String type,        // expense | income
        String merchant,
        String category,    // 分类 key，如 food/transport
        String note
    ) {}

    /** 对话（查账/记账），多轮由客户端维护 */
    public record ChatRequest(List<Message> messages, String context, String persona) {}

    public record Message(String role, String content) {}

    public record ChatResult(String reply, List<Action> actions) {}

    /**
     * 客户端执行的动作：
     * queryTx  查询账单（客户端查 Room 后把结果放进 messages 再发第二轮）
     * addTx    预填记账弹层（客户端打开 RecordSheet，不直接写库）
     */
    public record Action(String fn, Map<String, Object> params) {}

    /** 洞察卡 / 月度报告文案生成 */
    public record InsightRequest(String kind, List<String> ruleHits, String stats, String persona) {}

    public record InsightResult(String text) {}

    /** 截图识别（多模态）：客户端压缩上传，服务端持 key 调视觉模型 */
    public record VisionRequest(String image) {}

    public record VisionResult(Double amount, String merchant, String time) {}

    /**
     * 订阅识别（F7 真调 AI）：客户端送近几个月账单摘要
     * （每行 yyyy-MM-dd|商户|金额|分类key|账户id），服务端 AI 做语义判断，
     * 服务端再做连续性校验（月付需连续 ≥3 月），只认真出现过的固定支出。
     */
    public record SubscribeScanRequest(String txSummary, List<String> existing) {}

    public record SubscribeScanItem(
        String name,
        double amount,
        String category,
        String cycle,      // monthly | yearly
        int dayOfMonth,
        int dueMonth,      // yearly 1~12，monthly 为 0
        String accountId   // 从账单摘要回填的账户 id（识别不出为空）
    ) {}

    public record SubscribeScanResult(List<SubscribeScanItem> items) {}
}
