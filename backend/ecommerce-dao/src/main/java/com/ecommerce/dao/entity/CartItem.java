package com.ecommerce.dao.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 购物车条目实体，对应表 t_cart_item。
 */
@Data
@TableName("t_cart_item")
public class CartItem implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 购买数量 */
    private Integer quantity;

    /** 是否勾选 1是 0否 */
    private Integer checked;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}