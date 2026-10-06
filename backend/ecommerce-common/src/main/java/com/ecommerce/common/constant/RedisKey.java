package com.ecommerce.common.constant;

/**
 * Redis key 统一管理。
 * <p>所有 key 前缀统一为 {@code ec:}，方便与其他应用共用实例时做隔离扫描。</p>
 */
public final class RedisKey {

    private RedisKey() {
    }

    /**
     * JWT 令牌白名单（用于登出后立即失效）。
     * value = 剩余过期秒数，TTL 与令牌有效期一致。
     */
    public static final String TOKEN_WHITELIST = "ec:auth:token:";

    /** 商品详情缓存 */
    public static final String PRODUCT_DETAIL = "ec:product:detail:";

    /** 商品列表缓存 */
    public static final String PRODUCT_LIST = "ec:product:list:";

    /**
     * 库存预扣 key（Lua 脚本防超卖）。
     * value = 当前可售余量，下单时 DECRBY，失败则回补。
     */
    public static final String PRODUCT_STOCK = "ec:product:stock:";

    /** 分类列表缓存 */
    public static final String CATEGORY_LIST = "ec:category:list";

    /** 短信验证码 */
    public static final String SMS_CODE = "ec:sms:code:";

    /** 每日订单号自增序列 */
    public static final String ORDER_SEQ = "ec:order:seq:";
}