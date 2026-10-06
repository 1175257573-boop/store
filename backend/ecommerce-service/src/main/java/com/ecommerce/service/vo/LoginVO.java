package com.ecommerce.service.vo;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Builder;
import lombok.Data;

import java.io.Serializable;

/**
 * 登录成功返回体：令牌 + 用户简要信息。
 */
@Data
@Builder
public class LoginVO implements Serializable {

    /** JWT 令牌 */
    private String token;

    /** 用户 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    /** 用户名 */
    private String username;

    /** 昵称 */
    private String nickname;

    /** 头像 */
    private String avatar;

    /** 角色 0用户 1商家 2管理员 */
    private Integer role;

    /** 角色中文名 */
    private String roleText;

    /** 所属商家ID（商家才有） */
    private Long merchantId;

    /** 店铺名（商家才有），前端据此决定是否展示商家入口 */
    private String shopName;
}