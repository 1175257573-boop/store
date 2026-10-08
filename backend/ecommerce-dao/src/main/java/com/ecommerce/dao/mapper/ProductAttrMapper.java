package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.ProductAttr;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品结构化属性 Mapper。
 */
@Mapper
public interface ProductAttrMapper extends BaseMapper<ProductAttr> {

    List<ProductAttr> listByProduct(@Param("productId") Long productId);

    /**
     * 按属性键 + 数值区间查 —— 回答「5000mAh 以上有哪些」这类问题。
     *
     * <p>这是语义检索解决不了的：4000mAh 与 5000mAh 语义上"差不多"，
     * 但数值不满足用户条件。融合检索救不了，只能精确查。
     */
    @Select("SELECT a.*, p.name AS product_name "
            + "FROM t_product_attr a JOIN t_product p ON p.id = a.product_id "
            + "WHERE a.attr_key = #{attrKey} "
            + "  AND a.value_num IS NOT NULL "
            + "  AND a.value_num >= #{min} "
            + "ORDER BY a.value_num DESC LIMIT #{limit}")
    List<ProductAttr> findByAttrRange(@Param("attrKey") String attrKey,
                                       @Param("min") java.math.BigDecimal min,
                                       @Param("limit") int limit);

    /**
     * 按属性键 + 值等值查 —— 回答「有黑色的吗」。
     * 返回值里带商品名，供客服直接报出答案。
     */
    @Select("SELECT a.*, p.name AS product_name "
            + "FROM t_product_attr a JOIN t_product p ON p.id = a.product_id "
            + "WHERE a.attr_key = #{attrKey} "
            + "  AND a.attr_value LIKE CONCAT('%', #{value}, '%') "
            + "LIMIT #{limit}")
    List<ProductAttr> findByAttrValue(@Param("attrKey") String attrKey,
                                      @Param("value") String value,
                                      @Param("limit") int limit);
}