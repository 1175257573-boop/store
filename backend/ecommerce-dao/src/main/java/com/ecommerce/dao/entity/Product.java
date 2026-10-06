package com.ecommerce.dao.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品实体，对应表 t_product。
 */
@Data
@TableName("t_product")
public class Product implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /**
     * 所属商家ID，NULL 表示平台自营。
     * <p>商家端的所有商品操作都必须带这个条件，是数据隔离的关键字段。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 所属分类 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    private String name;

    /** 副标题 / 卖点 */
    private String subtitle;

    /** 商品详情 */
    private String description;

    private String mainImage;

    /** 销售价 */
    private BigDecimal price;

    /** 划线价 */
    private BigDecimal originPrice;

    /** 库存 */
    private Integer stock;

    /** 累计销量 */
    private Integer sales;

    private Integer viewCount;

    /** 1上架 0下架 */
    private Integer status;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 审核状态 0待审核 1通过 2拒绝 */
    private Integer auditStatus;

    /** 审核拒绝原因 */
    private String auditReason;
}