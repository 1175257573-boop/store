package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.AfterSale;
import com.ecommerce.dao.entity.ProductSku;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

/**
 * SKU Mapper。
 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSku> {

    @Select("SELECT * FROM t_product_sku WHERE product_id = #{productId} ORDER BY sort_order, id")
    List<ProductSku> selectByProductId(@Param("productId") Long productId);

    /** 按商家查全部 SKU（商品列表页展示规格数量用） */
    @Select("SELECT * FROM t_product_sku WHERE merchant_id = #{merchantId} "
            + "ORDER BY product_id, sort_order, id")
    List<ProductSku> selectByMerchant(@Param("merchantId") Long merchantId);

    /**
     * 扣减 SKU 库存（条件更新）。
     * <p>与秒杀模块同源思路：判断与扣减在同一条 SQL 内完成，
     * 影响 0 行即库存不足。不存在「先查后改」的窗口。</p>
     */
    @Update("UPDATE t_product_sku SET stock = stock - #{qty}, sales = sales + #{qty} "
            + "WHERE id = #{skuId} AND status = 1 AND stock >= #{qty}")
    int deductStock(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /** 回补 SKU 库存（取消订单 / 售后退款时调用） */
    @Update("UPDATE t_product_sku SET stock = stock + #{qty}, "
            + "sales = GREATEST(sales - #{qty}, 0) "
            + "WHERE id = #{skuId}")
    int restoreStock(@Param("skuId") Long skuId, @Param("qty") Integer qty);

    /** 修改价格与库存（商家编辑 SKU） */
    @Update("UPDATE t_product_sku SET price = #{price}, stock = #{stock}, sku_code = #{skuCode}, "
            + "status = #{status} "
            + "WHERE id = #{skuId} AND merchant_id = #{merchantId}")
    int updateSku(@Param("skuId") Long skuId,
                  @Param("merchantId") Long merchantId,
                  @Param("price") BigDecimal price,
                  @Param("stock") Integer stock,
                  @Param("skuCode") String skuCode,
                  @Param("status") Integer status);

    /** 商品下架时禁用其所有 SKU */
    @Update("UPDATE t_product_sku SET status = 0 WHERE product_id = #{productId}")
    int disableByProduct(@Param("productId") Long productId);

    /** 商品上架时启用其所有 SKU */
    @Update("UPDATE t_product_sku SET status = 1 WHERE product_id = #{productId}")
    int enableByProduct(@Param("productId") Long productId);
}
