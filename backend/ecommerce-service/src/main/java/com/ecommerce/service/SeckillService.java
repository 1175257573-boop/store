package com.ecommerce.service;

import com.ecommerce.dao.entity.SeckillActivity;
import com.ecommerce.dao.entity.SeckillOrder;
import com.ecommerce.dao.entity.SeckillPreDeduct;
import com.ecommerce.dao.entity.SeckillStock;
import com.ecommerce.service.dto.SeckillRequestDTO;
import com.ecommerce.service.vo.SeckillResultVO;

import java.util.List;
import java.util.Map;

/**
 * 秒杀服务接口（方案 C：Redis 挡流量 + DB 保正确）。
 */
public interface SeckillService {

    /**
     * 发起秒杀。
     *
     * <p>同步链路只做四件事，全部是毫秒级：</p>
     * <ol>
     *   <li>校验活动时间</li>
     *   <li>Redis 防重占位（SET NX EX）</li>
     *   <li>Redis Lua 预扣库存（原子）</li>
     *   <li>写消息队列，立即返回</li>
     * </ol>
     * <p>订单落库由消费者异步完成，不占用用户请求的连接和线程。</p>
     */
    SeckillResultVO seckill(SeckillRequestDTO dto);

    /**
     * 查询秒杀结果（供前端轮询）。
     *
     * @param requestId 前端请求唯一ID
     */
    SeckillResultVO queryResult(String requestId);

    /**
     * 支付秒杀订单。
     */
    void payOrder(Long orderId);

    /**
     * 主动取消秒杀订单，回补库存。
     */
    void cancelOrder(Long orderId);

    /**
     * 查我的秒杀订单。
     */
    List<SeckillOrder> listMyOrders(Long userId);

    /* ==================== 管理与运维 ==================== */

    /**
     * 初始化活动库存到 Redis（活动开始前调用）。
     */
    void initActivityStock(Long activityId);

    /**
     * 重置活动（清缓存 + 恢复 DB 库存），供压测重复使用。
     */
    void resetActivity(Long activityId);

    /**
     * 重建活动并指定库存（压测用）。
     * <p>要验证「少量库存 + 高并发」下的防超卖，必须能把库存调到很小。
     * 例如库存 50、并发 200，才能真正逼近超卖边界。</p>
     *
     * @param totalStock  新的总库存
     * @param bucketCount 分桶数量
     */
    void rebuildActivity(Long activityId, Long skuId, int totalStock, int bucketCount);

    /**
     * 查活动库存详情（含分桶明细）。
     */
    Map<String, Object> getStockDetail(Long activityId, Long skuId);

    /**
     * 消费待下单消息（由定时任务或压测脚本触发）。
     *
     * @return 本次成功落单的数量
     */
    int consumePendingOrders(int batchSize);

    /**
     * 多消费者并发消费。
     *
     * <p>用 RPOPLPUSH 原子取消息，多消费者不会重复消费同一条。</p>
     *
     * @param batchSize 本轮最多消费条数
     * @param consumers 并发消费者数
     * @return 成功落单数
     */
    int consumeBatch(int batchSize, int consumers);

    /**
     * 恢复僵死消息：把处理中队列的消息扫回主队列。
     * <p>消费者在「已取走未 ack」时崩溃，消息会卡在处理中队列，
     * 必须靠这个兜底，否则订单永久丢失。</p>
     */
    int recoverStale(int limit);

    /**
     * 死信队列重投：把死信重新放回主队列。
     * <p>修复线上问题后调用，让失败的消息重新走一遍。</p>
     */
    int requeueDeadLetters(int limit);

    /**
     * 运行时监控指标。
     */
    java.util.Map<String, Object> getMetrics(Long activityId, Long skuId);

    /**
     * 对账：校准 Redis 与 DB 差异，输出报告。
     */
    Map<String, Object> reconcile(Long activityId);

    /**
     * 补偿：回补「已预扣但未落单」的库存。
     *
     * @return 补偿的记录数
     */
    int compensateUnsettled(Long activityId, int batchSize);
}
