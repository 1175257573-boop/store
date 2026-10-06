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
 * 售后单实体，对应表 t_after_sale。
 *
 * <p>状态流转：0待处理 → 1已同意（等待退款）→ 3已完成
 * 或 0待处理 → 2已拒绝 / 4用户撤销。</p>
 */
@Data
@TableName("t_after_sale")
public class AfterSale implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String saleNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long orderId;

    private String orderNo;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 商品名快照：商品改名后历史售后单仍显示当时的名称 */
    private String productName;

    private Integer quantity;

    private BigDecimal amount;

    /** 1仅退款 2退货退款 */
    private Integer type;

    private String reason;

    private String remark;

    private String images;

    /** 0待处理 1已同意 2已拒绝 3已完成 4已撤销 */
    private Integer status;

    private String auditRemark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 状态中文映射 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "待处理";
            case 1 -> "已同意待退款";
            case 2 -> "已拒绝";
            case 3 -> "已完成";
            case 4 -> "已撤销";
            default -> "未知";
        };
    }

    /** 售后类型中文映射 */
    public static String typeText(Integer type) {
        return Integer.valueOf(2).equals(type) ? "退货退款" : "仅退款";
    }
}
