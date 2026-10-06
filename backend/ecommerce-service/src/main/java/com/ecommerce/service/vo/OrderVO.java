package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ecommerce.dao.entity.Order;
import com.ecommerce.dao.entity.OrderItem;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 订单 VO：订单主信息 + 明细列表。
 */
@Data
public class OrderVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 所属商家ID：商家端据此过滤自己的订单 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private BigDecimal totalAmount;

    private BigDecimal payAmount;

    /** 0待付款 1已付款 2已发货 3已完成 4已取消 */
    private Integer status;

    /** 状态中文描述 */
    private String statusText;

    private String receiver;

    private String phone;

    private String address;

    private String remark;

    private LocalDateTime payTime;

    private LocalDateTime shipTime;

    private LocalDateTime finishTime;

    private LocalDateTime cancelTime;

    private LocalDateTime createTime;

    /** 订单明细 */
    private List<OrderItemVO> items;

    /** 订单条目总数 */
    private Integer totalQuantity;

    /** 组装 VO */
    public static OrderVO from(Order order, List<OrderItem> orderItems) {
        OrderVO vo = new OrderVO();
        vo.setId(order.getId());
        vo.setOrderNo(order.getOrderNo());
        vo.setUserId(order.getUserId());
        vo.setMerchantId(order.getMerchantId());
        vo.setTotalAmount(order.getTotalAmount());
        vo.setPayAmount(order.getPayAmount());
        vo.setStatus(order.getStatus());
        vo.setStatusText(statusText(order.getStatus()));
        vo.setReceiver(order.getReceiver());
        vo.setPhone(order.getPhone());
        vo.setAddress(order.getAddress());
        vo.setRemark(order.getRemark());
        vo.setPayTime(order.getPayTime());
        vo.setShipTime(order.getShipTime());
        vo.setFinishTime(order.getFinishTime());
        vo.setCancelTime(order.getCancelTime());
        vo.setCreateTime(order.getCreateTime());
        if (orderItems != null && !orderItems.isEmpty()) {
            vo.setItems(orderItems.stream().map(OrderItemVO::from).toList());
            vo.setTotalQuantity(orderItems.stream().map(OrderItem::getQuantity).reduce(0, Integer::sum));
        } else {
            vo.setItems(List.of());
            vo.setTotalQuantity(0);
        }
        return vo;
    }

    /** 订单状态中文映射 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待付款";
            case 1 -> "待发货";
            case 2 -> "待收货";
            case 3 -> "已完成";
            case 4 -> "已取消";
            default -> "未知";
        };
    }
}