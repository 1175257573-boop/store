package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ecommerce.dao.entity.CartItem;
import com.ecommerce.dao.entity.Product;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 购物车条目 VO：条目信息 + 商品快照 + 小计。
 */
@Data
public class CartItemVO implements Serializable {

    /** 购物车条目 ID（用于勾选 / 改数量 / 删除） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long cartId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    private String productName;

    private String productImage;

    private String productSubtitle;

    private BigDecimal productPrice;

    /** 当前库存，库存不足时前端置灰 */
    private Integer stock;

    private Integer quantity;

    private Integer checked;

    /** 小计 = 单价 × 数量 */
    private BigDecimal subtotal;

    /** 库存是否充足 */
    private Boolean stockEnough;

    public static CartItemVO from(CartItem item, Product product) {
        CartItemVO vo = new CartItemVO();
        vo.setCartId(item.getId());
        vo.setProductId(item.getProductId());
        vo.setQuantity(item.getQuantity());
        vo.setChecked(item.getChecked());
        if (product != null) {
            vo.setProductName(product.getName());
            vo.setProductImage(product.getMainImage());
            vo.setProductSubtitle(product.getSubtitle());
            vo.setProductPrice(product.getPrice());
            vo.setStock(product.getStock());
            vo.setStockEnough(product.getStock() != null && product.getStock() >= item.getQuantity());
            vo.setSubtotal(product.getPrice().multiply(BigDecimal.valueOf(item.getQuantity())));
        }
        return vo;
    }
}