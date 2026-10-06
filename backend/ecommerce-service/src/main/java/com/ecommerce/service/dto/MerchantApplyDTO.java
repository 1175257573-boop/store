package com.ecommerce.service.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.io.Serializable;

/**
 * 商家入驻申请 DTO。
 */
@Data
public class MerchantApplyDTO implements Serializable {

    @NotBlank(message = "店铺名称不能为空")
    @Size(max = 50, message = "店铺名称最长 50 字")
    private String shopName;

    @Size(max = 500, message = "店铺简介最长 500 字")
    private String shopDesc;

    @NotBlank(message = "联系人不能为空")
    @Size(max = 50, message = "联系人最长 50 字")
    private String contactName;

    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String contactPhone;

    /** 经营类目 1个人 2企业 */
    private Integer businessType = 1;

    @Size(max = 50, message = "营业执照号最长 50 字")
    private String licenseNo;

    @Size(max = 30, message = "身份证号最长 30 位")
    private String idCard;
}
