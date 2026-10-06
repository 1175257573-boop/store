package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.ProductSpecValue;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品规格值 Mapper。
 */
@Mapper
public interface ProductSpecValueMapper extends BaseMapper<ProductSpecValue> {

    @Select("SELECT * FROM t_product_spec_value WHERE spec_id IN "
            + "(SELECT id FROM t_product_spec WHERE product_id = #{productId}) "
            + "ORDER BY sort_order, id")
    List<ProductSpecValue> selectByProductId(@Param("productId") Long productId);
}
