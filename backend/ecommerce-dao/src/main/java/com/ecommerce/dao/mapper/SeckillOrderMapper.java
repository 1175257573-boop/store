package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.SeckillOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 秒杀订单 Mapper。
 */
@Mapper
public interface SeckillOrderMapper extends BaseMapper<SeckillOrder> {

    /**
     * 支付：只有「待支付」能改成「已支付」。
     * <p>{@code AND status = 0} 是状态机守卫，影响 0 行说明已被处理过
     * （并发或重复调用），调用方据此判断是否需要重复释放库存。</p>
     */
    @Update("UPDATE t_seckill_order SET status = 1, pay_time = #{now} "
            + "WHERE id = #{orderId} AND status = 0")
    int markPaid(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    /**
     * 取消订单（用户主动取消）。
     * <p>状态机守卫保证只从「待支付」流转，影响 0 行即表示已处理过。</p>
     */
    @Update("UPDATE t_seckill_order SET status = 2, cancel_time = #{now} "
            + "WHERE id = #{orderId} AND status = 0")
    int cancelOrder(@Param("orderId") Long orderId, @Param("now") LocalDateTime now);

    /**
     * 超时关闭（补偿任务调用）。
     * <p>条件里带 {@code create_time < #{deadline}}，只处理真正超时的订单。</p>
     * <p>SQL 含裸 {@code <}，必须用 script 包裹（详见 SeckillActivityMapper 注释）。</p>
     */
    @Update("<script>"
            + "UPDATE t_seckill_order SET status = 3, cancel_time = #{now} "
            + "WHERE status = 0 AND create_time &lt; #{deadline}"
            + "</script>")
    int closeTimeoutOrders(@Param("deadline") LocalDateTime deadline,
                           @Param("now") LocalDateTime now);

    /**
     * 查待支付且已超时的订单（补偿任务扫描用）。
     * <p>分批查，避免一次拉太多撑爆内存。</p>
     */
    @Select("<script>"
            + "SELECT * FROM t_seckill_order WHERE status = 0 AND create_time &lt; #{deadline} "
            + "ORDER BY id LIMIT #{limit}"
            + "</script>")
    List<SeckillOrder> selectTimeoutOrders(@Param("deadline") LocalDateTime deadline,
                                           @Param("limit") Integer limit);

    @Select("SELECT * FROM t_seckill_order WHERE order_no = #{orderNo}")
    SeckillOrder selectByOrderNo(@Param("orderNo") String orderNo);

    /** 查我的秒杀订单 */
    @Select("SELECT * FROM t_seckill_order WHERE user_id = #{userId} "
            + "ORDER BY id DESC LIMIT #{limit}")
    List<SeckillOrder> selectByUser(@Param("userId") Long userId,
                                    @Param("limit") Integer limit);

    /**
     * 按预扣流水号反查订单（消费端幂等第二层：状态机拦截）。
     * <p>MQ 重复投递时，同一个 preDeductNo 只能落一个订单。</p>
     */
    @Select("SELECT * FROM t_seckill_order WHERE pre_deduct_no = #{preDeductNo} LIMIT 1")
    SeckillOrder selectByPreDeductNo(@Param("preDeductNo") String preDeductNo);

    /**
     * 统计：按状态分组计数（对账任务用）。
     */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_seckill_order "
            + "WHERE activity_id = #{activityId} GROUP BY status")
    List<Map<String, Object>> countByStatus(@Param("activityId") Long activityId);

    /**
     * <b>对账核心断言</b>：某活动已成功落单的总量（已支付 + 待支付）。
     * <p>这个数字必须等于 DB 库存表 {@code total_stock - available}，
     * 不等就说明有超卖或丢单。</p>
     */
    @Select("SELECT COUNT(*) FROM t_seckill_order "
            + "WHERE activity_id = #{activityId} AND status IN (0, 1)")
    int countValidOrders(@Param("activityId") Long activityId);
}
