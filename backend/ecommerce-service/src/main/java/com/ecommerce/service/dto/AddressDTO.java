package com.ecommerce.service.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 收货地址新增/编辑 DTO。
 */
@Data
public class AddressDTO {

    @NotBlank(message = "收货人不能为空")
    @Size(max = 50, message = "收货人最长 50 个字符")
    private String receiver;

    @NotBlank(message = "联系电话不能为空")
    @Pattern(regexp = "^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @NotBlank(message = "省份不能为空")
    private String province;

    @NotBlank(message = "城市不能为空")
    private String city;

    @NotBlank(message = "区县不能为空")
    private String district;

    @NotBlank(message = "详细地址不能为空")
    @Size(max = 200, message = "详细地址最长 200 个字符")
    private String detail;

    /** 是否设为默认地址 1是 0否 */
    @Min(value = 0, message = "默认值只能为 0 或 1")
    @Max(value = 1, message = "默认值只能为 0 或 1")
    private Integer isDefault;
}