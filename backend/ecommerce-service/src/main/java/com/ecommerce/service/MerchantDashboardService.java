package com.ecommerce.service;

import java.util.Map;

/**
 * 商家数据看板接口。
 */
public interface MerchantDashboardService {

    /**
     * 经营概览。
     * <p>所有销售额口径都只算「已支付及之后」的订单——
     * 待付款订单随时可能取消，算进去会让数据虚高。</p>
     */
    Map<String, Object> getOverview();

    /** 近 N 天销售趋势（折线图） */
    Map<String, Object> getSalesTrend(int days);

    /** 商品销量排行 */
    Map<String, Object> getTopProducts(int limit);

    /** 各状态订单与售后数量（角标） */
    Map<String, Object> getBadges();
}
