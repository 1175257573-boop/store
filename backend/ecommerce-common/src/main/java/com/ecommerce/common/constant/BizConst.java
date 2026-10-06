package com.ecommerce.common.constant;

/**
 * 业务枚举常量：订单状态、购物车勾选状态等。
 */
public final class BizConst {

    private BizConst() {
    }

    /* ---------------- 订单状态 ---------------- */
    /** 待付款 */
    public static final int ORDER_UNPAID = 0;
    /** 已付款 */
    public static final int ORDER_PAID = 1;
    /** 已发货 */
    public static final int ORDER_SHIPPED = 2;
    /** 已完成 */
    public static final int ORDER_FINISHED = 3;
    /** 已取消 */
    public static final int ORDER_CANCELLED = 4;

    /* ---------------- 用户状态 ---------------- */
    /** 正常 */
    public static final int USER_NORMAL = 1;
    /** 禁用 */
    public static final int USER_DISABLED = 0;

    /* ---------------- 商品状态 ---------------- */
    /** 上架 */
    public static final int PRODUCT_ON_SHELF = 1;
    /** 下架 */
    public static final int PRODUCT_OFF_SHELF = 0;

    /* ---------------- 秒杀活动状态 ---------------- */
    /** 未开始 */
    public static final int ACTIVITY_NOT_START = 0;
    /** 进行中 */
    public static final int ACTIVITY_RUNNING = 1;
    /** 已结束 */
    public static final int ACTIVITY_FINISHED = 2;
    /** 已取消 */
    public static final int ACTIVITY_CANCELLED = 3;

    /* ---------------- 秒杀订单状态 ---------------- */
    /**
     * 秒杀订单：待支付。
     * <p>与普通订单的 {@link #ORDER_UNPAID} 数值相同但语义不同，
     * 单独定义常量是为了避免两套状态机被误混用。</p>
     */
    public static final int SECKILL_ORDER_UNPAID = 0;
    /** 秒杀订单：已支付 */
    public static final int SECKILL_ORDER_PAID = 1;
    /** 秒杀订单：已取消 */
    public static final int SECKILL_ORDER_CANCELLED = 2;
    /** 秒杀订单：已超时关闭（补偿任务触发） */
    public static final int SECKILL_ORDER_TIMEOUT = 3;

    /* ---------------- 预扣流水状态 ---------------- */
    /** 已预扣未落单（对账时会挑出超时的这批做补偿） */
    public static final int PREDEDUCT_PENDING = 0;
    /** 已落单 */
    public static final int PREDEDUCT_ORDERED = 1;
    /** 已回补 */
    public static final int PREDEDUCT_RESTORED = 2;

    /* ---------------- 预扣方向 ---------------- */
    public static final int PREDEDUCT_DIRECTION_DEDUCT = 1;
    public static final int PREDEDUCT_DIRECTION_RESTORE = -1;

    /* ---------------- 其他 ---------------- */
    /** 布尔真 */
    public static final int YES = 1;
    /** 布尔假 */
    public static final int NO = 0;
    /** 单用户最大地址数量 */
    public static final int MAX_ADDRESS_COUNT = 20;
    /** 单次下单最大购买数量 */
    public static final int MAX_BUY_QUANTITY = 999;
}