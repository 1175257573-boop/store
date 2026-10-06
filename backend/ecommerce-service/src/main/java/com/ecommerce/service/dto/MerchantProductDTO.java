package com.ecommerce.service.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.List;

/**
 * 商家商品发布 / 编辑 DTO。
 */
@Data
public class MerchantProductDTO implements Serializable {

    @NotNull(message = "商品分类不能为空")
    private Long categoryId;

    @NotBlank(message = "商品名称不能为空")
    private String name;

    private String subtitle;

    private String description;

    private String mainImage;

    @NotNull(message = "售价不能为空")
    @DecimalMin(value = "0.01", message = "售价必须大于 0")
    private BigDecimal price;

    private BigDecimal originPrice;

    @NotNull(message = "库存不能为空")
    @Min(value = 0, message = "库存不能为负")
    private Integer stock;

    /**
     * 规格组列表。
     * <p>不传表示单规格商品，系统会自动生成一条 {@code specText="默认"} 的 SKU。</p>
     */
    @Valid
    private List<SpecGroup> specs;

    /**
     * SKU 列表。
     * <p>多规格时每个组合一条；单规格时只有一条且忽略 specText。</p>
     */
    @Valid
    @NotEmpty(message = "至少需要一个 SKU")
    private List<SkuItem> skus;

    /** 规格组 */
    @Data
    public static class SpecGroup implements Serializable {
        @NotBlank(message = "规格名不能为空")
        private String name;
        @NotEmpty(message = "规格值不能为空")
        private List<String> values;
    }

    /** SKU 项 */
    @Data
    public static class SkuItem implements Serializable {
        /** 规格组合文本，如"红色 / 256GB"；单规格留空 */
        private String specText;

        @NotNull(message = "SKU 价格不能为空")
        @DecimalMin(value = "0.01", message = "SKU 价格必须大于 0")
        private BigDecimal price;

        @NotNull(message = "SKU 库存不能为空")
        @Min(value = 0, message = "SKU 库存不能为负")
        private Integer stock;

        private String skuCode;

        /** 1启用 0禁用 */
        private Integer status = 1;
    }
}
