package com.ecommerce.common.constant;

/**
 * 角色常量。
 *
 * <p>本系统角色只有三类且固定，不建独立角色表——多一层关联只会让
 * 权限判定更复杂而不会更灵活。真正决定「能操作哪些数据」的是
 * {@code merchantId}，不是角色本身。</p>
 *
 * <p>权限模型：</p>
 * <pre>
 *   用户 (role=0)     只能操作自己的数据
 *   商家 (role=1)     只能操作 merchantId = 自己店铺的数据
 *   管理员 (role=2)   可操作全平台数据
 * </pre>
 */
public final class RoleConst {

    private RoleConst() {
    }

    /** 普通用户 */
    public static final int ROLE_USER = 0;

    /** 商家 */
    public static final int ROLE_MERCHANT = 1;

    /** 平台管理员 */
    public static final int ROLE_ADMIN = 2;

    /** 角色中文名 */
    public static String roleText(Integer role) {
        if (role == null) {
            return "用户";
        }
        return switch (role) {
            case ROLE_USER -> "普通用户";
            case ROLE_MERCHANT -> "商家";
            case ROLE_ADMIN -> "平台管理员";
            default -> "未知";
        };
    }
}
