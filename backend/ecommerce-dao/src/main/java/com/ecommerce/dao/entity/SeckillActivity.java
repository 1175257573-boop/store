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

    private String name;

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

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime endTime;

    /** 0未开始 1进行中 2已结束 3已取消 */
    private Integer status;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;
}
