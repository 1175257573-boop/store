package com.ecommerce.service.chat;

/**
 * 客服意图枚举。
 *
 * <p><b>声明顺序即优先级</b> —— 命中多个意图时按此顺序取第一个。
 * 更具体的意图必须排在更宽泛的前面：
 * 「哪个便宜点」问的是"买哪个划算"（对比），
 * 若 {@code PRICE} 排在 {@code COMPARE} 前会答成"这个多少钱"，
 * 这是<b>答错方向</b>，不是答得不够细。
 */
public enum ChatIntent {

    /** 对比与推荐 */
    COMPARE("compare", "对比与选购建议"),
    /** 价格 */
    PRICE("price", "商品价格与优惠"),
    /** 库存与发货 */
    STOCK("stock", "库存与发货"),
    /** 参数规格 */
    SPEC("spec", "商品参数与规格"),
    /** 退换货与保修 */
    AFTER_SALE("after_sale", "退换货与保修"),
    /** 支付与发票 */
    PAYMENT("payment", "支付发票与物流"),
    /** 优惠活动 */
    PROMOTION("promotion", "优惠与活动"),
    /** 怎么购买 */
    HOWTO("howto", "购买指引"),
    /** 转人工 */
    HUMAN("human", null),
    /** 问候 */
    GREETING("greeting", null),
    /** 未识别 */
    UNKNOWN("unknown", null);

    private final String code;
    private final String label;

    ChatIntent(String code, String label) {
        this.code = code;
        this.label = label;
    }

    public String getCode() {
        return code;
    }

    public String getLabel() {
        return label;
    }

    /**
     * 检索范围：决定答案从商品知识还是店铺知识取。
     */
    public enum Scope {
        /** 商品维度，走混合检索 + 商品过滤 */
        PRODUCT,
        /** 店铺维度，只查通用 FAQ */
        SHOP,
        /** 不检索，直接答话术 */
        NONE,
        /** 意图不明，先澄清 */
        BOTH
    }

    public Scope getScope() {
        return switch (this) {
            case PRICE, STOCK, SPEC, COMPARE -> Scope.PRODUCT;
            case AFTER_SALE, PAYMENT, PROMOTION, HOWTO -> Scope.SHOP;
            case HUMAN, GREETING -> Scope.NONE;
            case UNKNOWN -> Scope.BOTH;
        };
    }

    public static ChatIntent of(String code) {
        for (ChatIntent i : values()) {
            if (i.code.equals(code)) {
                return i;
            }
        }
        return UNKNOWN;
    }
}