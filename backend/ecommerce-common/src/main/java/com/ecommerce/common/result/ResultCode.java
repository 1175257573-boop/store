package com.ecommerce.common.result;

import lombok.Getter;

/**
 * 业务状态码枚举。
 * <p>约定：2xx 成功；4xx 客户端错误；5xx 服务端错误；1xxx 为电商业务自定义码。</p>
 */
@Getter
public enum ResultCode {

    /* ---------- 通用 ---------- */
    SUCCESS(200, "操作成功"),
    FAIL(500, "操作失败"),

    /* ---------- 参数与认证 ---------- */
    PARAM_ERROR(400, "请求参数有误"),
    UNAUTHORIZED(401, "登录已失效，请重新登录"),
    FORBIDDEN(403, "无权限访问"),
    NOT_FOUND(404, "请求的资源不存在"),
    METHOD_NOT_ALLOWED(405, "请求方式不被支持"),
    USERNAME_OR_PASSWORD_ERROR(1001, "用户名或密码错误"),
    ACCOUNT_DISABLED(1002, "账号已被禁用，请联系管理员"),
    ACCOUNT_NOT_EXIST(1003, "该账号尚未注册"),
    USERNAME_ALREADY_EXIST(1004, "该用户名已被占用"),
    TOKEN_INVALID(1005, "令牌无效，请重新登录"),
    TOKEN_EXPIRED(1006, "令牌已过期，请重新登录"),

    /* ---------- 商品与库存 ---------- */
    PRODUCT_NOT_EXIST(2001, "商品不存在或已下架"),
    PRODUCT_OFF_SHELF(2002, "商品已下架"),
    STOCK_NOT_ENOUGH(2003, "商品库存不足"),
    CATEGORY_NOT_EXIST(2004, "商品分类不存在"),

    /* ---------- 购物车 ---------- */
    CART_ITEM_NOT_EXIST(3001, "购物车中没有该商品"),
    CART_QUITY_INVALID(3002, "购买数量不合法"),

    /* ---------- 地址 ---------- */
    ADDRESS_NOT_EXIST(4001, "收货地址不存在"),
    ADDRESS_LIMIT_EXCEED(4002, "最多只能保存 20 条收货地址"),

    /* ---------- 订单 ---------- */
    ORDER_NOT_EXIST(5001, "订单不存在"),
    ORDER_STATUS_ERROR(5002, "订单状态不允许该操作"),
    ORDER_EMPTY(5003, "请先选择要结算的商品"),
    ORDER_ALREADY_PAID(5004, "订单已支付，请勿重复操作"),

    /* ---------- 角色与权限 ---------- */
    /** 需要商家身份 */
    MERCHANT_REQUIRED(6001, "该功能仅商家可用，请先入驻开店"),
    /** 需要管理员身份 */
    ADMIN_REQUIRED(6002, "该功能仅平台管理员可用"),
    /** 越权：数据不属于当前操作者 */
    DATA_NOT_BELONG_TO_YOU(6003, "无权操作该数据"),
    /** 店铺被冻结 */
    SHOP_FROZEN(6004, "店铺已被冻结，请联系平台"),
    /** 尚未入驻 */
    NOT_MERCHANT_YET(6005, "请先提交入驻申请并通过审核"),
    /** 申请审核中 */
    APPLY_PENDING(6006, "入驻申请审核中，请耐心等待"),
    /** 重复入驻 */
    ALREADY_MERCHANT(6007, "您已入驻开店，无法重复申请"),
    /** 入驻申请不存在 */
    APPLY_NOT_EXIST(6008, "入驻申请不存在"),

    /* ---------- 商品审核 ---------- */
    PRODUCT_AUDIT_PENDING(7001, "商品审核中，通过后自动上架"),
    PRODUCT_AUDIT_REJECTED(7002, "商品审核未通过"),
    PRODUCT_NOT_BELONG_TO_MERCHANT(7003, "该商品不属于本店"),
    SKU_NOT_EXIST(7004, "SKU 不存在"),
    SKU_STOCK_NOT_ENOUGH(7005, "SKU 库存不足"),

    /* ---------- 售后 ---------- */
    AFTER_SALE_NOT_EXIST(8001, "售后单不存在"),
    AFTER_SALE_STATUS_ERROR(8002, "售后单状态不允许该操作"),
    AFTER_SALE_AMOUNT_ERROR(8003, "退款金额超出可退范围");

    private final Integer code;
    private final String message;

    ResultCode(Integer code, String message) {
        this.code = code;
        this.message = message;
    }
}