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
 * 秒杀订单明细实体，对应表 t_seckill_order_item。
 *
 * <p><b>为什么必须独立于普通订单明细</b>：{@code t_order_item} 的外键指向
 * {@code t_order}（普通订单表），秒杀订单在 {@code t_seckill_order}。
 * 两者混用会触发外键约束失败，且两套订单的生命周期与查询维度都不同。</p>
 */
@Data
@TableName("t_seckill_order_item")
public class SeckillOrderItem implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品名（下单快照） */
    private String productName;

    private String productImage;

    private BigDecimal productPrice;

    private Integer quantity;

    private BigDecimal subtotal;
}
