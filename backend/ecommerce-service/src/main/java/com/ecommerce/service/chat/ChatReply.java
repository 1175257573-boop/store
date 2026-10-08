package com.ecommerce.service.chat;

import java.util.List;

/**
 * 客服回复结果。
 *
 * @param sessionId 会话 ID，前端后续请求带上
 * @param reply     回复正文
 * @param intent    识别出的意图 code
 * @param intentLabel 意图的中文名
 * @param confidence 置信度
 * @param matched   命中的关键词/编号，用于「为什么这么答」的追溯
 * @param source    识别来源：menu / menu_loose / keyword / context
 * @param suggestions 建议的追问选项（数字字符串），前端渲染成可点击的按钮
 * @param citations 答案引用的知识块/问答 ID，用于溯源与埋点
 * @param replyTimeMs 检索+生成耗时，前端可显示「思考中」
 */
public record ChatReply(
        String sessionId,
        String reply,
        String intent,
        String intentLabel,
        double confidence,
        String matched,
        String source,
        List<String> suggestions,
        List<String> citations,
        long replyTimeMs
) {
}