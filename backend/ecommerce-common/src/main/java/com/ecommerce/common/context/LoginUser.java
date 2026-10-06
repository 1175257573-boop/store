package com.ecommerce.common.context;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;

/**
 * 登录用户上下文。
 * <p>除 userId 外还携带角色与商家 ID，让 Service 层能直接做权限判定，
 * 不必每处都回查数据库。</p>
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class LoginUser implements Serializable {

    private Long userId;

    private String username;

    private String nickname;

    /** 角色：0用户 1商家 2管理员 */
    private Integer role;

    /**
     * 所属商家 ID。
     * <p>只有 role=1 且店铺状态正常时才有值。商家的一切数据查询都必须
     * 带这个条件，否则就是越权。</p>
     */
    private Long merchantId;

    /** 兼容旧的三参构造 */
    public LoginUser(Long userId, String username, String nickname) {
        this(userId, username, nickname, null, null);
    }

    public boolean isAdmin() {
        return role != null && role == 2;
    }

    public boolean isMerchant() {
        return role != null && role == 1;
    }
}
