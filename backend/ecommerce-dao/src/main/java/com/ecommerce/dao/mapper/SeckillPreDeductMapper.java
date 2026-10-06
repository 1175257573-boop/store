package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.SeckillPreDeduct;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 秒杀预扣流水 Mapper（对账依据）。
 */
@Mapper
public interface SeckillPreDeductMapper extends BaseMapper<SeckillPreDeduct> {

    /**
     * 标记为已落单。
     * <p>{@code AND status = 0} 状态机守卫：重复消费时第二次影响 0 行，
     * 不会把已回补的记录又改成已落单。</p>
     */
    @Update("UPDATE t_seckill_pre_deduct SET status = 1, order_id = #{orderId} "
            + "WHERE pre_deduct_no = #{preDeductNo} AND status = 0")
    int markOrdered(@Param("preDeductNo") String preDeductNo,
                    @Param("orderId") Long orderId);

    /**
     * 标记为已回补。
     * <p>同样带状态守卫，保证回补只执行一次，
     * 重复回补会让库存越补越多。</p>
     */
    @Update("UPDATE t_seckill_pre_deduct SET status = 2 "
            + "WHERE pre_deduct_no = #{preDeductNo} AND status = 0")
    int markRestored(@Param("preDeductNo") String preDeductNo);

    @Select("SELECT * FROM t_seckill_pre_deduct WHERE pre_deduct_no = #{preDeductNo}")
    SeckillPreDeduct selectByNo(@Param("preDeductNo") String preDeductNo);

    /**
     * 扫「已预扣但未落单且已超时」的流水（补偿任务用）。
     * <p>这批记录就是「Redis 扣了但订单没成」的部分，必须回补库存。</p>
     * <p>SQL 含裸 {@code <}，必须用 script 包裹（详见 SeckillActivityMapper 注释）。</p>
     */
    @Select("<script>"
            + "SELECT * FROM t_seckill_pre_deduct WHERE status = 0 AND create_time &lt; #{deadline} "
            + "ORDER BY id LIMIT #{limit}"
            + "</script>")
    List<SeckillPreDeduct> selectUnsettledBefore(@Param("deadline") LocalDateTime deadline,
                                                 @Param("limit") Integer limit);

    /**
     * 统计预扣流水（对账用）。
     * <p>预扣总量（direction=1）应该等于 成功订单数 + 已回补数。</p>
     */
    @Select("SELECT direction, status, COUNT(*) AS cnt FROM t_seckill_pre_deduct "
            + "WHERE activity_id = #{activityId} GROUP BY direction, status")
    List<Map<String, Object>> statFlow(@Param("activityId") Long activityId);

    /**
     * 某活动某 SKU 的预扣总量。
     */
    @Select("SELECT COUNT(*) FROM t_seckill_pre_deduct "
            + "WHERE activity_id = #{activityId} AND sku_id = #{skuId} AND direction = 1")
    int countPreDeduct(@Param("activityId") Long activityId,
                       @Param("skuId") Long skuId);

    /**
     * 统计某活动的「净预扣量」（预扣 - 回补），应等于成功订单数。
     */
    @Select("SELECT IFNULL(SUM(direction), 0) FROM t_seckill_pre_deduct "
            + "WHERE activity_id = #{activityId} AND status != 2")
    int countNetPreDeduct(@Param("activityId") Long activityId);
}