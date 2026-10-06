package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品规格值实体，对应表 t_product_spec_value。
 */
@Data
@TableName("t_product_spec_value")
public class ProductSpecValue implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long specId;

    /** 规格值，如"红色"、"256GB" */
    private String value;

    private Integer sortOrder;
}
