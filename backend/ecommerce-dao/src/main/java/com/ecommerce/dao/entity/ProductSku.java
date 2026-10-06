package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品 SKU 实体，对应表 t_product_sku。
 *
 * <p>多规格商品（如"颜色×容量"的组合）每个组合一行，各有独立价格与库存。
 * 单规格商品也用这张表，只有一行 {@code spec_text} 为"默认"。</p>
 */
@Data
@TableName("t_product_sku")
public class ProductSku implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 冗余商家ID：按商家查 SKU 时无需 join 商品表 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 规格组合文本，如"红色 / 256GB"；单规格为"默认" */
    private String specText;

    private BigDecimal price;

    private Integer stock;

    private Integer sales;

    private String skuCode;

    /** 1启用 0禁用 */
    private Integer status;

    private Integer sortOrder;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}
