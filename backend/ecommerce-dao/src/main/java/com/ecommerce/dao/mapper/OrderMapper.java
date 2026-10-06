package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.Order;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单主表 Mapper。
 */
@Mapper
public interface OrderMapper extends BaseMapper<Order> {

    /** 按订单号查询 */
    @Select("SELECT * FROM t_order WHERE order_no = #{orderNo} LIMIT 1")
    Order selectByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按状态流转订单状态（带原状态校验，防并发重复操作）。
     * <p>如支付时传 currentStatus=0, targetStatus=1，若订单已被其他请求改过则影响行数为 0。</p>
     *
     * @return 影响行数，1=成功，0=状态已变更
     */
    @Update("""
            <script>
            UPDATE t_order
               SET status = #{targetStatus}
             <if test="targetStatus == 1">, pay_time = #{now}</if>
             <if test="targetStatus == 2">, ship_time = #{now}</if>
             <if test="targetStatus == 3">, finish_time = #{now}</if>
             <if test="targetStatus == 4">, cancel_time = #{now}</if>
             WHERE id = #{id}
            <if test="currentStatus != null">AND status = #{currentStatus}</if>
            </script>
            """)
    int updateStatus(@Param("id") Long id,
                     @Param("currentStatus") Integer currentStatus,
                     @Param("targetStatus") Integer targetStatus,
                     @Param("now") LocalDateTime now);

    /** 统计各状态订单数量，用于个人中心订单角标 */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_order WHERE user_id = #{userId} GROUP BY status")
    List<java.util.Map<String, Object>> countByStatus(@Param("userId") Long userId);

    /**
     * 商家端订单列表：只看自己店铺的订单。
     * <p>merchantId 为 null 表示不限制（管理员看全平台）。
     * 这个条件是数据隔离的关键，漏了就是越权。</p>
     */
    @Select("<script>SELECT * FROM t_order "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "  <if test='keyword != null and keyword.length() > 0'>"
            + "    AND (order_no LIKE CONCAT('%', #{keyword}, '%')"
            + "         OR receiver LIKE CONCAT('%', #{keyword}, '%'))"
            + "  </if>"
            + "</where> ORDER BY id DESC</script>")
    java.util.List<com.ecommerce.dao.entity.Order> selectMerchantOrders(
            @Param("merchantId") Long merchantId,
            @Param("status") Integer status,
            @Param("keyword") String keyword);

    /**
     * 商家发货。
     * <p>状态机守卫：只有 1（已付款待发货）能改，重复点击影响 0 行。
     * 同时带 merchant_id 条件，越权发货会被直接拦在 SQL 层。</p>
     */
    @Update("<script>UPDATE t_order SET status = 2, ship_time = #{now}, "
            + "ship_company = #{shipCompany}, ship_no = #{shipNo} "
            + "WHERE id = #{orderId} AND status = 1 "
            + "<if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "</script>")
    int shipOrder(@Param("orderId") Long orderId,
                  @Param("merchantId") Long merchantId,
                  @Param("shipCompany") String shipCompany,
                  @Param("shipNo") String shipNo,
                  @Param("now") java.time.LocalDateTime now);

    /**
     * 按商家 + 订单ID 查（越权校验用）。
     * <p>绝不能用 selectById —— 它不区分归属。</p>
     */
    @Select("<script>SELECT * FROM t_order WHERE id = #{orderId} "
            + "<if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "</script>")
    com.ecommerce.dao.entity.Order selectByIdAndMerchant(@Param("orderId") Long orderId,
                                                          @Param("merchantId") Long merchantId);

    /**
     * 店铺销售额统计（看板用）。
     * <p>只统计已支付及之后的状态：待付款订单随时可能取消，
     * 算进销售额会让数据虚高。</p>
     */
    @Select("<script>SELECT IFNULL(SUM(pay_amount), 0) AS amount, COUNT(*) AS cnt FROM t_order "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  AND status IN (1, 2, 3)"
            + "  <if test='start != null'> AND create_time &gt;= #{start} </if>"
            + "</where></script>")
    java.util.Map<String, Object> sumSalesByMerchant(
            @Param("merchantId") Long merchantId,
            @Param("start") java.time.LocalDateTime start);

    /** 各状态订单数（商家端角标） */
    @Select("<script>SELECT status, COUNT(*) AS cnt FROM t_order "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "</where> GROUP BY status</script>")
    java.util.List<java.util.Map<String, Object>> countMerchantOrdersByStatus(
            @Param("merchantId") Long merchantId);

    /** 近 N 天每日销售额趋势（看板折线图） */
    @Select("<script>SELECT DATE(create_time) AS d, IFNULL(SUM(pay_amount), 0) AS amount, "
            + "COUNT(*) AS cnt FROM t_order "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  AND status IN (1, 2, 3)"
            + "  AND create_time &gt;= DATE_SUB(CURDATE(), INTERVAL #{days} DAY)"
            + "</where> GROUP BY DATE(create_time) ORDER BY d</script>")
    java.util.List<java.util.Map<String, Object>> dailySalesTrend(
            @Param("merchantId") Long merchantId,
            @Param("days") int days);

    /** 商品销量排行（看板用） */
    @Select("<script>SELECT oi.product_id, oi.product_name, "
            + "SUM(oi.quantity) AS qty, SUM(oi.subtotal) AS amount "
            + "FROM t_order_item oi "
            + "JOIN t_order o ON o.id = oi.order_id "
            + "<where>"
            + "  <if test='merchantId != null'> AND oi.merchant_id = #{merchantId} </if>"
            + "  AND o.status IN (1, 2, 3)"
            + "</where> GROUP BY oi.product_id, oi.product_name "
            + "ORDER BY qty DESC LIMIT #{limit}</script>")
    java.util.List<java.util.Map<String, Object>> topProducts(
            @Param("merchantId") Long merchantId,
            @Param("limit") int limit);
}
