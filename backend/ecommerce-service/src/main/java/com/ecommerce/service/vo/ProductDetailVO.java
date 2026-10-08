package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.ecommerce.dao.entity.Product;
import com.ecommerce.dao.entity.ProductSku;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

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

    /** 所属店铺ID */
    private Long merchantId;

    /** 店铺名 */
    private String shopName;

    /** 店铺简介（详情页展示，来源 t_merchant.shop_desc） */
    private String shopDesc;

    /** 店铺评分（0-5，一位小数） */
    private BigDecimal shopScore;

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

    /**
     * 可售规格列表。
     * <p>由 {@code ProductServiceImpl.getDetail} 单独查询填充 ——
     * {@link #from(Product, String)} 只负责商品自身字段，不碰 SKU。
     * 之所以不塞进 from()：SKU 是独立表，要走 skuMapper 查，
     * 而 from() 是纯 POJO 转换，不该有 IO 行为。
     * <p>为空表示该商品未配置多规格，前端应按单规格（price/stock）展示。
     */
    private List<ProductSku> skuList;

    public static ProductDetailVO from(Product product, String categoryName) {
        ProductDetailVO vo = new ProductDetailVO();
        vo.setId(product.getId());
        vo.setCategoryId(product.getCategoryId());
        vo.setCategoryName(categoryName);
        vo.setMerchantId(product.getMerchantId());
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