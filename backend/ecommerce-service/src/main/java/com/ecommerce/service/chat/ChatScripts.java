package com.ecommerce.service.chat;

/**
 * 客服话术。
 *
 * <p><b>三条硬规则</b>（每条都对应一类真实事故）：
 * <ol>
 *   <li><b>答不出来就说答不出来</b> —— 知识盲区不编。
 *       客服说错参数直接导致退货纠纷，编造的代价远大于拒答。</li>
 *   <li><b>该澄清就澄清，不是硬答</b> —— 「我确认不了」和
 *       「你还没告诉我哪款」对用户完全不同：前者是我无知，后者是需要澄清。</li>
 *   <li><b>标题与内容必须一致</b> —— 答「关于曜石…」却讲极光的参数，
 *       用户会当成曜石的。这是开发中出现过的最严重缺陷。</li>
 * </ol>
 */
public final class ChatScripts {

    private ChatScripts() {
    }

    public static final String NO_ANSWER =
            "这个我暂时确认不了，不方便给您不准确的信息。\n"
            + "建议咨询人工客服确认，或者换个说法再问一下。";

    public static final String TRANSFER =
            "好的，为您转接人工客服，请稍候。\n"
            + "（演示环境暂未接入人工坐席，接入后会继续为您服务）";

    public static final String UNKNOWN_PREFIX =
            "抱歉，我没太理解您的意思。\n"
            + "您可以回复下面的序号，或直接描述问题：";

    /**
     * 开场白。
     *
     * <p>选项设计的四个考量：
     * <ul>
     *   <li>6 个以内 —— 超过用户会直接跳过</li>
     *   <li>不放「其他」选项 —— 那是兜底逻辑，不该在菜单里占位置</li>
     *   <li>数字而非字母 —— 手机打字母要切输入法</li>
     *   <li>按咨询频次排序 —— 越常见越靠前</li>
     * </ul>
     */
    public static String greeting(String productName) {
        StringBuilder sb = new StringBuilder();
        sb.append("您好，我是智能客服。请问您想了解哪方面？\n");
        sb.append("直接回复序号即可，也可以用自己的话描述。\n");
        if (productName != null && !productName.isBlank()) {
            sb.append("当前商品：").append(productName).append('\n');
        }
        sb.append("\n您可以回复：\n");
        sb.append("  1. ").append(ChatIntent.PRICE.getLabel()).append('\n');
        sb.append("  2. ").append(ChatIntent.STOCK.getLabel()).append('\n');
        sb.append("  3. ").append(ChatIntent.SPEC.getLabel()).append('\n');
        sb.append("  4. ").append(ChatIntent.COMPARE.getLabel()).append('\n');
        sb.append("  5. ").append(ChatIntent.AFTER_SALE.getLabel()).append('\n');
        sb.append("  6. ").append(ChatIntent.PAYMENT.getLabel()).append('\n');
        sb.append("\n例如回复「2」查看库存与发货，或直接说「这个多少钱」。");
        return sb.toString();
    }

    /** 追问后的简化菜单 —— 只留高频项，避免刷屏。 */
    public static String followupMenu() {
        return "您还想了解：\n"
                + "  1. " + ChatIntent.PRICE.getLabel() + "\n"
                + "  2. " + ChatIntent.STOCK.getLabel() + "\n"
                + "  3. " + ChatIntent.SPEC.getLabel() + "\n"
                + "  4. " + ChatIntent.COMPARE.getLabel() + "\n"
                + "  0. 转人工";
    }

    /**
     * 澄清话术：用户选了意图但没说清对象时，反问而不是硬答。
     */
    public static String clarify(ChatIntent intent) {
        return switch (intent) {
            case PRICE -> "想了解价格的话，可以告诉我具体是哪款商品。";
            case STOCK -> "想查库存的话，请告诉我具体的商品名称，我帮您看是否现货。";
            case SPEC -> "可以，请问您想了解哪方面？比如电池容量、尺寸、材质或噪音。";
            case COMPARE -> "请告诉我您关注的两款商品或您的预算范围，我来帮您对比。";
            case AFTER_SALE -> "我可以帮您了解退换货与保修政策。请问是商品质量问题，还是七天无理由退货？";
            case PAYMENT -> "请问您想了解支付方式还是发票开具？";
            case PROMOTION -> "目前平台的优惠以活动专区展示为准，您想了解优惠券还是满减活动？";
            case HOWTO -> "点击商品详情页的「加入购物车」或「立即购买」即可下单。需要我介绍具体流程吗？";
            default -> "请告诉我您想了解的具体商品。";
        };
    }

    /** 指定商品但没检索到知识时 —— 如实说，不拿别的商品充数。 */
    public static String productNoRecord(String productName) {
        return "关于「" + productName + "」，我暂时没检索到相关记录。\n"
                + "可能是该商品的资料还没完善，建议咨询人工客服确认。";
    }

    /** 把 FAQ 答案整理成客服口吻。 */
    public static String faqAnswer(String question, String answer) {
        String q = question.replaceAll("[？?]+$", "");
        return "关于「" + q + "」：\n" + answer;
    }

    /** 块类型的展示名 —— 让回答有结构，而不是一大段文字。 */
    public static String chunkTypeLabel(String chunkType) {
        return switch (chunkType == null ? "" : chunkType) {
            case "spec" -> "规格";
            case "stock" -> "库存与发货";
            case "usecase" -> "适用场景";
            case "warranty" -> "售后";
            default -> "相关信息";
        };
    }
}