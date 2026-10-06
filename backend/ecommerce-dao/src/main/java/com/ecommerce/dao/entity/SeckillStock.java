package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 秒杀库存实体，对应表 t_seckill_stock。
 *
 * <p><b>复合主键说明</b>：表的主键是 (activity_id, sku_id)，
 * 但 MyBatis-Plus 的实体只支持单个 {@code @TableId}。
 * 第二个字段标为 {@code @TableField} 排除出主键逻辑，
 * 查询/更新用 Mapper 里手写的 SQL（按两列定位），不走单主键的 selectById。</p>
 */
@Data
@TableName("t_seckill_stock")
public class SeckillStock implements Serializable {

    @TableId(type = IdType.INPUT)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long activityId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long skuId;

    /**
     * 该 SKU 总库存（不随售卖变化，对账基准）。
     * <p>不变量：{@code available + locked + sold == totalStock}</p>
     */
    private Integer totalStock;

    /**
     * DB 层可用库存，必须 >= 0。
     * <p>扣减时用 {@code WHERE available >= qty} 做条件校验，
     * 影响行数为 0 即判定售罄。这是最终正确性依据，不可妥协。</p>
     */
    private Integer available;

    /** 已锁定（下单中未支付）数量 */
    private Integer locked;

    /** 已售出数量 */
    private Integer sold;

    private Long version;

    /** 活动状态中文映射 */
    public static String activityStatusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 0 -> "未开始";
            case 1 -> "进行中";
            case 2 -> "已结束";
            case 3 -> "已取消";
            default -> "未知";
        };
    }
}