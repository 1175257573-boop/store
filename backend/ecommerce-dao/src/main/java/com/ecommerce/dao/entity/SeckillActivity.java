package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 秒杀活动实体，对应表 t_seckill_activity。
 */
@Data
@TableName("t_seckill_activity")
public class SeckillActivity implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 活动业务编号 */
    private String activityNo;

    /**
     * 归属商家ID，NULL 表示平台自建活动。
     * <p>商家只能看到和操作自己店铺的活动，平台自建活动对所有商家可见但不可改。</p>
     */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    private String name;

    /** 活动封面 */
    private String coverImage;

    /** 活动说明 */
    private String description;

    /**
     * 总库存。
     * <p>这是<b>对账基准</b>，售卖过程中永不改变。
     * 任意时刻都应满足：{@code available + locked + sold == totalStock}。</p>
     */
    private Integer totalStock;

    /**
     * 库存分桶数量，用于打散 Redis 热点 key。
     * <p>把 totalStock 拆成 bucketCount 个桶，请求随机路由。</p>
     */
    private Integer bucketCount;

    /**
     * 每人限购数量。
     * <p>1 = 每人只能抢 1 件，是秒杀最常见的规则。
     * 由 t_seckill_order 上的唯一索引 {@code uk_user_activity_sku} 兜底保证。</p>
     */
    private Integer limitPerUser;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 0未开始 1进行中 2已结束 3已取消 */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
