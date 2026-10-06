package com.ecommerce.common.context;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;

/**
 * 登录用户上下文持有者。
 * <p>使用 ThreadLocal 绑定当前请求的用户信息，请求结束时务必在拦截器中调用
 * {@link #clear()} 移除，否则线程池复用会导致上下文串号。</p>
 */
public class UserContextHolder {

    private static final ThreadLocal<LoginUser> CONTEXT = new ThreadLocal<>();

    private UserContextHolder() {
    }

    /** 写入上下文 */
    public static void set(LoginUser user) {
        CONTEXT.set(user);
    }

    /** 获取上下文，可能为 null（未登录接口） */
    public static LoginUser get() {
        return CONTEXT.get();
    }

    /**
     * 获取当前用户 ID，未登录直接抛 401。
     * 用于所有必须登录才能访问的 Service 方法。
     */
    public static Long requireUserId() {
        LoginUser user = CONTEXT.get();
        if (user == null || user.getUserId() == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user.getUserId();
    }

    /** 判断是否已登录 */
    public static boolean isLogin() {
        return CONTEXT.get() != null;
    }

    /**
     * 获取当前商家 ID，非商家则抛异常。
     * <p>所有商家端接口的第一步都调它，保证越权请求在进入业务逻辑前就被拦下，
     * 而不是等到 SQL 里漏了 merchant_id 条件才发现。</p>
     */
    public static Long requireMerchantId() {
        LoginUser user = CONTEXT.get();
        // 同样区分未登录（401）与已登录非商家（6001），
        // 前端据此决定是跳登录页还是提示去入驻
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!user.isMerchant() || user.getMerchantId() == null) {
            throw new BusinessException(ResultCode.MERCHANT_REQUIRED);
        }
        return user.getMerchantId();
    }

    /**
     * 数据隔离范围：商家只能看自己，管理员可看全部。
     * <p>返回 {@code null} 表示不限制（管理员）。把它塞进每个
     * 「查自己数据」的 SQL 条件里，比在每个方法里写 if-else 更难漏。</p>
     */
    public static Long dataScopeMerchantId() {
        LoginUser user = CONTEXT.get();
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        return user.isAdmin() ? null : user.getMerchantId();
    }

    /** 要求管理员身份 */
    public static void requireAdmin() {
        LoginUser user = CONTEXT.get();
        // 区分两种情况：未登录（401，提示去登录）与已登录但不是管理员（403，提示无权限）。
        // 混为一谈会让前端在未登录时误以为是权限问题，做不出「跳登录」的正确引导。
        if (user == null) {
            throw new BusinessException(ResultCode.UNAUTHORIZED);
        }
        if (!user.isAdmin()) {
            throw new BusinessException(ResultCode.ADMIN_REQUIRED);
        }
    }

    /** 清理上下文，必须在请求结束时调用 */
    public static void clear() {
        CONTEXT.remove();
    }
}