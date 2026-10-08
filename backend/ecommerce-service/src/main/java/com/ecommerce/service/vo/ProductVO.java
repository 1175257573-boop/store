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

    /** 所属店铺ID —— 商品按店铺维度筛选与展示的基础字段 */
    private Long merchantId;

    /** 店铺名 —— 前端商品卡片展示「某某旗舰店」，省一次查店铺接口 */
    private String shopName;

    private String name;

    private String subtitle;

    private String mainImage;

    private BigDecimal price;

    private BigDecimal originPrice;

    private Integer stock;

    private Integer sales;

    private Integer status;

    /** 由实体 + 分类名转换 */
    /**
     * 商品实体转 VO。
     *
     * @param categoryName 分类名，可为 null
     * @param shopName     店铺名，可为 null（列表页批量回填用）
     */
    public static ProductVO from(Product product, String categoryName, String shopName) {
        ProductVO vo = new ProductVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setMerchantId(product.getMerchantId());
        vo.setShopName(shopName);
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
        return from(product, null, null);
    }

    public static ProductVO from(Product product, String categoryName) {
        return from(product, categoryName, null);
    }
}