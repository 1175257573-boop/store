package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.ProductSpec;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * 商品规格组 Mapper。
 */
@Mapper
public interface ProductSpecMapper extends BaseMapper<ProductSpec> {

    @Select("SELECT * FROM t_product_spec WHERE product_id = #{productId} ORDER BY sort_order, id")
    List<ProductSpec> selectByProductId(@Param("productId") Long productId);
}
