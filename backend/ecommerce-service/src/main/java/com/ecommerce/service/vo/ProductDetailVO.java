package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ecommerce.dao.entity.Product;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 商品详情 VO。
 */
@Data
public class ProductDetailVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    private String categoryName;

    private String name;

    private String subtitle;

    private String description;

    private String mainImage;

    private BigDecimal price;

    private BigDecimal originPrice;

    private Integer stock;

    private Integer sales;

    private Integer viewCount;

    private Integer status;

    private LocalDateTime createTime;

    public static ProductDetailVO from(Product product, String categoryName) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setName(product.getName());
        vo.setSubtitle(product.getSubtitle());
        vo.setDescription(product.getDescription());
        vo.setMainImage(product.getMainImage());
        vo.setPrice(product.getPrice());
        vo.setOriginPrice(product.getOriginPrice());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setViewCount(product.getViewCount());
        vo.setStatus(product.getStatus());
        vo.setCreateTime(product.getCreateTime());
        return vo;
    }
}