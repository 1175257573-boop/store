package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnore;

/**
 * 商品规格组实体，对应表 t_product_spec。
 */
@Data
@TableName("t_product_spec")
public class ProductSpec implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 规格名，如"颜色"、"容量" */
    private String name;

    private Integer sortOrder;

    /**
     * 该规格下的所有值（不落库，查询时组装）。
     * 用 @JsonIgnore 让它只服务内部逻辑，不会出现在响应体里。
     */
    @JsonIgnore
    private List<ProductSpecValue> values;
}
