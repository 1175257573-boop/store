package com.ecommerce.service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

/**
 * 个人资料修改 DTO。
 * <p>刻意只暴露可改字段，不复用 User 实体 ——
 * 实体里有 password、status、username 等字段，直接接收实体等于把提权口子留给前端。</p>
 */
@Data
public class ProfileUpdateDTO {

    @NotBlank(message = "昵称不能为空")
    @Size(max = 20, message = "昵称最长 20 个字符")
    private String nickname;

    @Pattern(regexp = "^$|^1[3-9]\\d{9}$", message = "手机号格式不正确")
    private String phone;

    @Email(message = "邮箱格式不正确")
    private String email;

    private String avatar;

    @Min(value = 0, message = "性别取值不合法")
    @Max(value = 2, message = "性别取值不合法")
    private Integer gender;

    /** 生日，格式 yyyy-MM-dd */
    @Pattern(regexp = "^$|^\\d{4}-\\d{2}-\\d{2}$", message = "生日格式应为 yyyy-MM-dd")
    private String birthday;
}