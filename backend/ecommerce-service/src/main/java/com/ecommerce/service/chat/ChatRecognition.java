package com.ecommerce.service.chat;

/**
 * 意图识别结果。
 *
 * @param intent      识别出的意图
 * @param confidence  置信度 0~1，决定是否直接采纳还是要澄清
 * @param matched     命中的关键词/编号，用于回答「为什么这么答」与日志追溯
 * @param source      识别来源：menu / menu_loose / keyword / context
 */
public record ChatRecognition(
        ChatIntent intent,
        double confidence,
        String matched,
        String source
) {
    public boolean isConfident() {
        return confidence >= 0.7;
    }
}