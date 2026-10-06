package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 店铺实体，对应表 t_merchant。
 */
@Data
@TableName("t_merchant")
public class Merchant implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 店主用户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String shopName;

    private String shopLogo;

    private String shopDesc;

    private String contactName;

    private String contactPhone;

    /** 经营类目 1个人 2企业 */
    private Integer businessType;

    private String licenseNo;

    /** 1正常 2冻结 3已注销 */
    private Integer status;

    private Integer totalProduct;

    private Long totalOrder;

    private BigDecimal totalSales;

    private BigDecimal score;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime createTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime updateTime;

    /** 状态中文映射 */
    public static String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        return switch (status) {
            case 1 -> "正常";
            case 2 -> "已冻结";
            case 3 -> "已注销";
            default -> "未知";
        };
    }
}
