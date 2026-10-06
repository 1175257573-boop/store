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
 * 订单主表实体，对应表 t_order。
 * <p>收货人 / 电话 / 地址均为下单时刻快照，地址后续被用户修改不影响历史订单展示。</p>
 */
@Data
@TableName("t_order")
public class Order implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 业务订单号 */
    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /**
     * 所属商家ID。
     * <p>下单时从商品快照而来，商家端查订单必须按它过滤。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 订单总金额 */
    private BigDecimal totalAmount;

    /** 实付金额 */
    private BigDecimal payAmount;

    /** 0待付款 1已付款 2已发货 3已完成 4已取消 */
    private Integer status;

    /** 收货人（下单快照） */
    private String receiver;

    /** 联系电话（下单快照） */
    private String phone;

    /** 完整地址（下单快照） */
    private String address;

    /** 用户备注 */
    private String remark;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;

    /** 快递公司（商家发货时填写） */
    private String shipCompany;

    /** 快递单号 */
    private String shipNo;
}