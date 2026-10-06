package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.AfterSale;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * 售后 Mapper。
 */
@Mapper
public interface AfterSaleMapper extends BaseMapper<AfterSale> {

    /**
     * 商家同意售后。
     * <p>状态机守卫：只有 0（待处理）能改，重复点击影响 0 行。</p>
     */
    @Update("UPDATE t_after_sale SET status = 1, audit_remark = #{remark}, audit_time = #{now} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND status = 0")
    int approve(@Param("id") Long id,
                @Param("merchantId") Long merchantId,
                @Param("remark") String remark,
                @Param("now") LocalDateTime now);

    /** 商家拒绝售后 */
    @Update("UPDATE t_after_sale SET status = 2, audit_remark = #{remark}, audit_time = #{now} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND status = 0")
    int reject(@Param("id") Long id,
               @Param("merchantId") Long merchantId,
               @Param("remark") String remark,
               @Param("now") LocalDateTime now);

    /** 完成退款（同意后实际打款完成） */
    @Update("UPDATE t_after_sale SET status = 3, audit_time = #{now} "
            + "WHERE id = #{id} AND merchant_id = #{merchantId} AND status = 1")
    int complete(@Param("id") Long id,
                 @Param("merchantId") Long merchantId,
                 @Param("now") LocalDateTime now);

    /** 用户撤销 */
    @Update("UPDATE t_after_sale SET status = 4 WHERE id = #{id} AND user_id = #{userId} AND status = 0")
    int revoke(@Param("id") Long id, @Param("userId") Long userId);

    /** 查该订单已有的售后单（防重复申请） */
    @Select("SELECT * FROM t_after_sale WHERE order_id = #{orderId} "
            + "AND product_id = #{productId} AND status IN (0, 1)")
    List<AfterSale> selectActiveByOrderAndProduct(@Param("orderId") Long orderId,
                                                  @Param("productId") Long productId);

    @Select("SELECT * FROM t_after_sale WHERE id = #{id}")
    AfterSale selectById(@Param("id") Long id);

    /** 售后列表（商家端按状态筛选） */
    @Select("<script>SELECT * FROM t_after_sale "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  <if test='userId != null'> AND user_id = #{userId} </if>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "</where> ORDER BY id DESC</script>")
    List<AfterSale> selectByCondition(@Param("merchantId") Long merchantId,
                                      @Param("userId") Long userId,
                                      @Param("status") Integer status);

    /** 各状态售后单数（商家端角标） */
    @Select("SELECT status, COUNT(*) AS cnt FROM t_after_sale "
            + "WHERE merchant_id = #{merchantId} GROUP BY status")
    List<Map<String, Object>> countByStatus(@Param("merchantId") Long merchantId);
}
