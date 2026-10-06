package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 秒杀订单实体，对应表 t_seckill_order。
 *
 * <p><b>幂等设计</b>：表上有唯一索引
 * {@code uk_user_activity_sku (user_id, activity_id, sku_id)}。
 * 这是消费端幂等的<b>唯一可靠兜底</b>——Redis 防重可能失效、
 * MQ 可能重复投递，但这个索引在数据库层面绝不允许同一用户重复下单。</p>
 */
@Data
@TableName("t_seckill_order")
public class SeckillOrder implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private Integer quantity;

    /** 成交金额（下单快照） */
    private BigDecimal amount;

    /** 0待支付 1已支付 2已取消 3已超时关闭 */
    private Integer status;

    /** 请求唯一ID（前端生成，用于消费幂等的第一层拦截） */
    private String requestId;

    /** 预扣流水号，关联 t_seckill_pre_deduct */
    private String preDeductNo;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime payTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime cancelTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 订单状态中文映射 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待支付";
            case 1 -> "已支付";
            case 2 -> "已取消";
            case 3 -> "已超时关闭";
            default -> "未知";
        };
    }
}