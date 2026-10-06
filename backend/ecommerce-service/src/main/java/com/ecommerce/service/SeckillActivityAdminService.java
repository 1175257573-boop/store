package com.ecommerce.service;

import com.ecommerce.service.dto.SeckillActivityDTO;
import com.ecommerce.dao.entity.SeckillActivity;

import java.util.List;
import java.util.Map;

/**
 * 秒杀活动管理接口（平台管理员 / 商家发布活动）。
 */
public interface SeckillActivityAdminService {

    /**
     * 发布秒杀活动。
     * <p>内部完成：活动落库 → 库存记录落库 → 库存预热到 Redis 分桶。
     * 库存预热是必须的——线上开始瞬间才初始化会有大量请求读到 key 不存在。</p>
     */
    Long publish(SeckillActivityDTO dto);

    /**
     * 编辑活动（仅未开始的可以改）。
     * <p>已开始的活动不允许改库存，避免线上数据与缓存不一致。</p>
     */
    void update(Long activityId, SeckillActivityDTO dto);

    /**
     * 活动列表。
     * <p>商家只能看到自己的活动；管理员传 null 看全部。</p>
     */
    List<SeckillActivity> list(Integer status, String keyword);

    /**
     * 活动详情（含各商品的库存明细）。
     */
    Map<String, Object> getDetail(Long activityId);

    /**
     * 上线 / 下线。
     * <p>上线时把库存预热到 Redis；下线时清掉 Redis key 释放内存。</p>
     */
    void changeStatus(Long activityId, Integer status);

    /**
     * 删除活动。
     * <p>已有订单的活动不允许删除（历史订单会断链），只能下线。</p>
     */
    void delete(Long activityId);

    /**
     * 重置活动库存（压测 / 活动重开前用）。
     * <p>把可用库存恢复为总库存，同时清 Redis 重新分桶。</p>
     */
    void resetStock(Long activityId);

    /**
     * 活动实时库存概览：可用 / 锁定 / 已售 / 订单数。
     */
    Map<String, Object> getStockSummary(Long activityId);
}
