package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商家入驻申请实体，对应表 t_merchant_apply。
 *
 * <p>{@code idCard} 标了 {@link JsonIgnore} —— 身份证号是敏感信息，
 * 列表接口不该返回，只有审核详情页才需要，且应由单独的接口按需提供。</p>
 */
@Data
@TableName("t_merchant_apply")
public class MerchantApply implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String shopName;

    private String shopDesc;

    private String contactName;

    private String contactPhone;

    /** 1个人 2企业 */
    private Integer businessType;

    private String licenseNo;

    /** 身份证号，敏感字段，不出现在任何返回体中 */
    @JsonIgnore
    private String idCard;

    /** 0待审核 1通过 2拒绝 3已撤销 */
    private Integer status;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long auditUserId;

    private String auditRemark;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditTime;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

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
            case 0 -> "待审核";
            case 1 -> "已通过";
            case 2 -> "已拒绝";
            case 3 -> "已撤销";
            default -> "未知";
        };
    }
}
