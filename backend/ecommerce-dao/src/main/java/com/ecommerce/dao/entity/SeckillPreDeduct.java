package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀预扣流水实体，对应表 t_seckill_pre_deduct。
 *
 * <p><b>为什么必须有这张表</b>：Redis 预扣和 DB 落单是两个独立动作，
 * 中间可能失败。没有流水表，事后无法回答「Redis 扣了多少、DB 落了多少、
 * 差额该怎么补」。这张表是<b>对账的唯一依据</b>。</p>
 *
 * <p>状态流转：{@code 0 已预扣未落单 → 1 已落单}，
 * 或 {@code 0 已预扣未落单 → 2 已回补}。停在 0 且超时的是需要补偿的对象。</p>
 */
@Data
@TableName("t_seckill_pre_deduct")
public class SeckillPreDeduct implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 预扣流水号（全局唯一） */
    private String preDeductNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 命中的库存分桶下标 */
    private Integer bucketIndex;

    private Integer quantity;

    /** 方向 1预扣 -1回补 */
    private Integer direction;

    /** 0已预扣未落单 1已落单 2已回补 */
    private Integer status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;
}
