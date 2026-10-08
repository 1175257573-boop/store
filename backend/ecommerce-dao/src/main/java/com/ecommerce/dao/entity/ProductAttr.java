package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品结构化属性。
 *
 * <p>客服需要精确匹配的场景：「有黑色吗」「多少毫安」「5000mAh 以上」。
 * 这类问题不能靠语义相似度 —— 用户问「5000mAh 以上」时，
 * 向量检索会把 4000mAh 也召回（语义上"差不多"但数值不满足）。
 * 必须靠 {@code valueNum} 做区间查询。
 */
@Data
@TableName("t_product_attr")
public class ProductAttr implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 属性键，如 brand / model / battery / screen_size */
    private String attrKey;

    /** 属性显示名，如「电池容量」 */
    private String attrName;

    /** 属性值（展示用） */
    private String attrValue;

    /** 数值化后的值，用于区间查询与排序 */
    private BigDecimal valueNum;

    /** 单位，如 mAh / 英寸 / GB */
    private String unit;

    private String groupName;

    private Integer sortOrder;
}