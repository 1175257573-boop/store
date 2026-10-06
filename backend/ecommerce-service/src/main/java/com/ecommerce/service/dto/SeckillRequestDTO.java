package com.ecommerce.service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * 秒杀请求 DTO。
 */
@Data
public class SeckillRequestDTO {

    @NotNull(message = "活动ID不能为空")
    private Long activityId;

    @NotNull(message = "商品ID不能为空")
    private Long skuId;

    /**
     * 请求唯一 ID，由前端生成（UUID）。
     * <p>用于消费端幂等的第一层拦截：同一请求重复提交时，
     * 后端可据此识别并直接返回首次的结果。</p>
     */
    @NotNull(message = "请求ID不能为空")
    private String requestId;

    @Min(value = 1, message = "购买数量至少为 1")
    @Max(value = 1, message = "秒杀限购每人 1 件")
    private Integer quantity = 1;
}