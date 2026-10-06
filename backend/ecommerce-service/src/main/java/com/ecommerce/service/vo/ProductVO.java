package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ecommerce.dao.entity.Product;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;

/**
 * 商品列表项 VO。
 * <p>相比实体裁剪掉了 description 等大字段，同时补上分类名，
 * 列表页不查详情即可渲染。</p>
 */
@Data
public class ProductVO implements Serializable {

    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    /** 分类名称（联表补充） */
    private String categoryName;

    private String name;

    private String subtitle;

    private String mainImage;

    private BigDecimal price;

    private BigDecimal originPrice;

    private Integer stock;

    private Integer sales;

    private Integer status;

    /** 由实体 + 分类名转换 */
    public static ProductVO from(Product product, String categoryName) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setName(product.getName());
        vo.setSubtitle(product.getSubtitle());
        vo.setMainImage(product.getMainImage());
        vo.setPrice(product.getPrice());
        vo.setOriginPrice(product.getOriginPrice());
        vo.setStock(product.getStock());
        vo.setSales(product.getSales());
        vo.setStatus(product.getStatus());
        return vo;
    }

    public static ProductVO from(Product product) {
        return from(product, null);
    }
}