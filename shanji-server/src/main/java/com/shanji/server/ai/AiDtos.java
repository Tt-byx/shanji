package com.shanji.server.ai;

import java.util.List;
import java.util.Map;

/** AI 接口请求/响应 DTO */
public class AiDtos {

    /** 自然语言记账解析 */
    public record ParseRequest(String rawText, String persona) {}

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
}
