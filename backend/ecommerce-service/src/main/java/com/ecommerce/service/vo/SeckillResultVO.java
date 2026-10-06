package com.ecommerce.service.vo;

import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀结果码。
 *
 * <p>把「用户该看到什么」和「内部怎么扣库存」解耦：
 * 接口只返回业务结果码，具体文案由前端按 code 映射。</p>
 */
@Data
public class SeckillResultVO implements Serializable {

    /**
     * 结果码：
     * <ul>
     *   <li>0  排队中 —— 已入队，等待异步落单</li>
     *   <li>1  抢购成功</li>
     *   <li>2  已售罄</li>
     *   <li>3  重复提交（防重拦截）</li>
     *   <li>4  活动未开始</li>
     *   <li>5  活动已结束</li>
     *   <li>6  系统繁忙，请重试</li>
     *   <li>7  请求过于频繁（限流拦截）</li>
     *   <li>8  系统降级中（Redis 不可用，已切 DB 直连）</li>
     * </ul>
     */
    private Integer code;

    /** 提示文案 */
    private String message;

    /** 订单号（成功时返回，供用户查询订单） */
    private String orderNo;

    /** 订单ID */
    private Long orderId;

    /** 剩余库存（分桶总量，供前端展示「剩余 N 件」） */
    private Integer remaining;

    /** 是否走了降级路径（前端可据此提示用户稍后重试） */
    private Boolean degraded;

    public static SeckillResultVO of(int code, String message) {
        SeckillResultVO vo = new SeckillResultVO();
        vo.setCode(code);
        vo.setMessage(message);
        return vo;
    }

    public static SeckillResultVO queued() {
        return of(0, "抢购请求已提交，正在排队处理");
    }

    public static SeckillResultVO success(String orderNo, Long orderId) {
        SeckillResultVO vo = of(1, "抢购成功");
        vo.setOrderNo(orderNo);
        vo.setOrderId(orderId);
        return vo;
    }

    public static SeckillResultVO soldOut() {
        return of(2, "手慢了，商品已售罄");
    }

    public static SeckillResultVO duplicated() {
        return of(3, "您已参与过本次活动");
    }

    public static SeckillResultVO notStarted() {
        return of(4, "活动尚未开始");
    }

    public static SeckillResultVO ended() {
        return of(5, "活动已结束");
    }

    public static SeckillResultVO busy() {
        return of(6, "系统繁忙，请稍后重试");
    }

    /** 被限流拦截 */
    public static SeckillResultVO rateLimited() {
        return of(7, "请求过于频繁，请稍后再试");
    }

    /**
     * Redis 不可用，已降级到 DB 直连。
     * <p>此时仍可正常抢购（正确性由 DB 条件更新保证），
     * 但吞吐会大幅下降，所以要提示用户。</p>
     */
    public static SeckillResultVO degraded() {
        SeckillResultVO vo = of(8, "系统繁忙，正在排队中，请稍后查看结果");
        vo.setDegraded(true);
        return vo;
    }
}
