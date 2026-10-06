package com.ecommerce.service.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.List;

/**
 * 提交订单 DTO。
 */
@Data
public class OrderCreateDTO {

    /**
     * 来源类型：cart=从购物车结算（结算后清理对应条目），
     * buyNow=立即购买（需带 items）。
     */
    private String source;

    /** 立即购买场景的商品清单 */
    private List<BuyNowItem> items;

    /** 收货地址 ID */
    @NotNull(message = "请选择收货地址")
    private Long addressId;

    /** 订单备注 */
    private String remark;

    /**
     * 立即购买的单项。
     */
    @Data
    public static class BuyNowItem {
        @NotNull(message = "商品ID不能为空")
        private Long productId;
        @NotNull(message = "购买数量不能为空")
        private Integer quantity;
    }

    /** 校验：立即购买时必须有商品 */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @jakarta.validation.constraints.AssertTrue(message = "立即购买必须指定商品")
    public boolean isItemsValid() {
        return !"buyNow".equals(source) || (items != null && !items.isEmpty());
    }
}