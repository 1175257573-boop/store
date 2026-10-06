package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.Product;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 商品 Mapper。
 */
@Mapper
public interface ProductMapper extends BaseMapper<Product> {

    /**
     * 扣减库存（带条件更新，防超卖）。
     * <p>SQL 层面用 {@code WHERE stock >= #{quantity} 做乐观校验，
     * 配合数据库行锁保证并发安全；返回 0 即表示库存不足。</p>
     *
     * @return 影响行数，1=扣减成功，0=库存不足
     */
    @Update("UPDATE t_product SET stock = stock - #{quantity}, sales = sales + #{quantity} "
            + "WHERE id = #{id} AND stock >= #{quantity}")
    int deductStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 回补库存（订单取消 / 支付失败时调用）。
     */
    @Update("UPDATE t_product SET stock = stock + #{quantity}, sales = GREATEST(sales - #{quantity}, 0) "
            + "WHERE id = #{id}")
    int restoreStock(@Param("id") Long id, @Param("quantity") Integer quantity);

    /**
     * 浏览量 +1，用 SQL 原子自增避免读改写竞争。
     */
    @Update("UPDATE t_product SET view_count = view_count + 1 WHERE id = #{id}")
    int incrViewCount(@Param("id") Long id);

    /**
     * 商家端商品列表：只看自己的商品。
     * <p>merchantId 由 UserContextHolder.dataScopeMerchantId() 传入，
     * 管理员传 null 表示不限制。SQL 里必须带这个条件，否则就是越权。</p>
     */
    @Select("<script>SELECT * FROM t_product "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  <if test='categoryId != null'> AND category_id = #{categoryId} </if>"
            + "  <if test='status != null'> AND status = #{status} </if>"
            + "  <if test='auditStatus != null'> AND audit_status = #{auditStatus} </if>"
            + "  <if test='keyword != null and keyword.length() > 0'>"
            + "    AND name LIKE CONCAT('%', #{keyword}, '%')"
            + "  </if>"
            + "</where> ORDER BY id DESC</script>")
    java.util.List<com.ecommerce.dao.entity.Product> selectByMerchantCondition(
            @Param("merchantId") Long merchantId,
            @Param("categoryId") Long categoryId,
            @Param("status") Integer status,
            @Param("auditStatus") Integer auditStatus,
            @Param("keyword") String keyword);

    /**
     * 审核商品。
     * <p>状态机守卫：只有 0（待审核）能改，重复审核影响 0 行。</p>
     */
    @org.apache.ibatis.annotations.Update(
            "UPDATE t_product SET audit_status = #{auditStatus}, audit_reason = #{reason}, "
                    + "status = #{status} WHERE id = #{productId} AND audit_status = 0")
    int auditProduct(@Param("productId") Long productId,
                     @Param("auditStatus") Integer auditStatus,
                     @Param("reason") String reason,
                     @Param("status") Integer status);

    /**
     * 按商家 + 商品ID 查（越权校验用）。
     * <p>商家端所有按 ID 操作的接口都要用它，
     * 而不是 selectById —— 后者不区分归属，会被用来改别人的商品。</p>
     */
    @Select("<script>SELECT * FROM t_product WHERE id = #{productId} "
            + "<if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "</script>")
    com.ecommerce.dao.entity.Product selectByIdAndMerchant(@Param("productId") Long productId,
                                                           @Param("merchantId") Long merchantId);

    /** 商家商品数统计（看板用） */
    @Select("<script>SELECT COUNT(*) FROM t_product "
            + "<where>"
            + "  <if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "  <if test='auditStatus != null'> AND audit_status = #{auditStatus} </if>"
            + "</where></script>")
    int countByMerchant(@Param("merchantId") Long merchantId,
                        @Param("auditStatus") Integer auditStatus);

    /**
     * 统计待审核的商品数（管理员红点用）。
     * <p>只统计商家提交的商品（merchant_id 非空）——平台自营商品
     * 走的是另一套上架流程，不进审核队列。</p>
     */
    @Select("SELECT COUNT(*) FROM t_product "
            + "WHERE audit_status = 0 AND merchant_id IS NOT NULL")
    Long countPendingAudit();
}
