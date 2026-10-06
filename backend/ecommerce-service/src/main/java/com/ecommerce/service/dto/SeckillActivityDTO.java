package com.ecommerce.service.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 秒杀活动发布 DTO。
 */
@Data
public class SeckillActivityDTO implements Serializable {

    /** 活动编号，留空则自动生成（时间戳） */
    @Size(max = 40, message = "活动编号最长 40 字")
    private String activityNo;

    @NotBlank(message = "活动名称不能为空")
    @Size(max = 100, message = "活动名称最长 100 字")
    private String name;

    @Size(max = 255, message = "封面地址过长")
    private String coverImage;

    @Size(max = 500, message = "活动说明最长 500 字")
    private String description;

    @NotNull(message = "活动开始时间不能为空")
    private LocalDateTime startTime;

    @NotNull(message = "活动结束时间不能为空")
    private LocalDateTime endTime;

    /** 每人限购数量，1 = 每人 1 件 */
    @Min(value = 1, message = "每人限购至少 1 件")
    private Integer limitPerUser = 1;

    /**
     * 库存分桶数量。
     * <p>把总库存拆成 N 桶分摊到 Redis 不同 key，规避单 key 热点。
     * 经验值：QPS 上万用 50~100，小活动 10 足够。</p>
     */
    @Min(value = 1, message = "分桶数量至少为 1")
    private Integer bucketCount = 10;

    /**
     * 活动商品及各自的秒杀库存。
     * <p>一个活动可以放多个商品，每个商品有独立库存。</p>
     */
    @NotEmpty(message = "至少要选择一个活动商品")
    private List<SeckillGoods> goods;

    /**
     * 活动商品项。
     */
    @Data
    public static class SeckillGoods implements Serializable {

        @NotNull(message = "商品ID不能为空")
        private Long productId;

        /** 该商品的秒杀总价（可低于原价形成价格优势） */
        @NotNull(message = "秒杀价不能为空")
        private java.math.BigDecimal price;

        /** 该商品的秒杀库存量 */
        @NotNull(message = "秒杀库存不能为空")
        @Min(value = 1, message = "秒杀库存至少为 1")
        private Integer totalStock;
    }
}
