package com.ecommerce.service.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

import org.hibernate.validator.constraints.Length;

/**
 * 售后申请 DTO（用户侧）。
 */
@Data
public class AfterSaleApplyDTO implements Serializable {

    @NotNull(message = "订单ID不能为空")
    private Long orderId;

    @NotNull(message = "商品ID不能为空")
    private Long productId;

    @NotNull(message = "售后数量不能为空")
    @Min(value = 1, message = "售后数量至少为 1")
    @Max(value = 999, message = "售后数量过大")
    private Integer quantity;

    /** 退款金额，不能超过该商品的实付金额 */
    @NotNull(message = "退款金额不能为空")
    @DecimalMin(value = "0.01", message = "退款金额必须大于 0")
    private BigDecimal amount;

    /** 1仅退款 2退货退款 */
    private Integer type = 1;

    @NotBlank(message = "售后原因不能为空")
    @Length(max = 100, message = "售后原因最长 100 字")
    private String reason;

    @Length(max = 500, message = "补充说明最长 500 字")
    private String remark;

    /** 凭证图片，逗号分隔 */
    @Length(max = 1000, message = "图片地址过长")
    private String images;
}
