package com.ecommerce.dao.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 订单明细实体，对应表 t_order_item。
 * <p>商品名称 / 图片 / 单价均为下单时刻快照，历史订单展示不依赖商品表当前值。</p>
 */
@Data
@TableName("t_order_item")
public class OrderItem implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    /** 订单号（冗余字段，便于按单号直查明细） */
    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /**
     * 所属商家ID。
     * <p>冗余在明细上：商家端按商品维度统计销量时无需 join 订单表。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 商品名（下单快照） */
    private String productName;

    /** 商品图（下单快照） */
    private String productImage;

    /** 成交单价（下单快照） */
    private BigDecimal productPrice;

    private Integer quantity;

    /** 小计金额 */
    private BigDecimal subtotal;
}