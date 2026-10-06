package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.SeckillStock;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 秒杀库存 Mapper。
 */
@Mapper
public interface SeckillStockMapper extends BaseMapper<SeckillStock> {

    /**
     * 扣减可用库存（条件更新，防超卖的核心 SQL）。
     *
     * <p><b>为什么这一条 SQL 就能防超卖</b>：
     * 「判断库存是否够」和「扣减」在同一条语句内完成，
     * 由 InnoDB 行锁保证两个并发事务串行化。
     * 后到的请求在获得锁后会<b>重新求值</b> {@code available >= qty}，
     * 此时 available 已被前一个请求扣减，因此条件不成立、影响 0 行。</p>
     *
     * <p>不存在「先 select 再 update」的时间窗口，
     * 所以不需要分布式锁，也不需要乐观锁重试。</p>
     *
     * @return 影响行数：1=扣减成功，0=库存不足（已售罄）
     */
    @Update("UPDATE t_seckill_stock SET available = available - #{qty}, version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND available >= #{qty}")
    int deductAvailable(@Param("activityId") Long activityId,
                        @Param("skuId") Long skuId,
                        @Param("qty") Integer qty);

    /**
     * 回补可用库存（订单取消 / 超时关闭 / 落库失败补偿时调用）。
     * <p>{@code available = LEAST(available + qty, total_stock)} 是安全阀：
     * 重复回补时最多补到总库存，不会出现超发。</p>
     */
    @Update("UPDATE t_seckill_stock SET available = LEAST(available + #{qty}, total_stock), "
            + "version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int restoreAvailable(@Param("activityId") Long activityId,
                         @Param("skuId") Long skuId,
                         @Param("qty") Integer qty);

    /**
     * 支付成功：锁定转已售。
     *
     * <p><b>注意：这里绝对不能动 available。</b>
     * 账目流转是：</p>
     * <pre>
     *   下单时：available -= qty, locked += qty   （available 已扣过）
     *   支付时：locked  -= qty, sold    += qty   （available 保持不变）
     * </pre>
     * <p>如果支付时再写 {@code available += qty}，库存会被凭空多出来，
     * 直接破坏 {@code available + locked + sold == total_stock} 不变量，
     * 造成超卖。这是最容易写错的一处。</p>
     */
    @Update("UPDATE t_seckill_stock SET locked = GREATEST(locked - #{qty}, 0), "
            + "sold = sold + #{qty}, version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int confirmSold(@Param("activityId") Long activityId,
                    @Param("skuId") Long skuId,
                    @Param("qty") Integer qty);

    /**
     * 锁定库存（下单成功时调用，标记为待支付）。
     * <p>用于对账时区分「已下单未支付」和「纯可用」，
     * 便于精确定位哪些库存是被订单占住的。</p>
     */
    @Update("UPDATE t_seckill_stock SET locked = locked + #{qty}, version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int lockStock(@Param("activityId") Long activityId,
                  @Param("skuId") Long skuId,
                  @Param("qty") Integer qty);

    /**
     * 释放锁定（订单取消时调用）。
     * <p>{@code GREATEST(locked - qty, 0)} 防止重复释放把 locked 减成负数。</p>
     */
    @Update("UPDATE t_seckill_stock SET locked = GREATEST(locked - #{qty}, 0), "
            + "version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int releaseLock(@Param("activityId") Long activityId,
                    @Param("skuId") Long skuId,
                    @Param("qty") Integer qty);

    /**
     * 查库存（复合主键，手写 SQL）。
     */
    @Select("SELECT * FROM t_seckill_stock WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    SeckillStock selectByActivityAndSku(@Param("activityId") Long activityId,
                                        @Param("skuId") Long skuId);

    /**
     * 重置库存到初始状态（运营/压测用）。
     *
     * <p><b>为什么不能用 updateById</b>：本表主键是 (activity_id, sku_id) 复合键，
     * 而 MyBatis-Plus 的 {@code updateById} 只按 {@code @TableId} 标注的单个字段
     * 定位记录。activity_id 相同的活动下所有 SKU 会被一起更新，
     * 造成跨 SKU 的库存串改。复合主键必须手写 SQL 按两列定位。</p>
     */
    @Update("UPDATE t_seckill_stock SET available = #{total}, locked = 0, sold = 0, "
            + "version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int resetStock(@Param("activityId") Long activityId,
                   @Param("skuId") Long skuId,
                   @Param("total") Integer total);

    /**
     * 修改总库存（运营调整总盘子用）。
     * <p>与 {@link #resetStock} 分开：改总量和重置账目是两件事，
     * 压测重建活动时需要同时做，运营日常调量时只做前者。</p>
     */
    @Update("UPDATE t_seckill_stock SET total_stock = #{total}, version = version + 1 "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId}")
    int updateStockTotal(@Param("activityId") Long activityId,
                         @Param("skuId") Long skuId,
                         @Param("total") Integer total);
}