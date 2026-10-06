package com.ecommerce.common.util;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 业务工具方法。
 */
public final class BizUtil {

    private BizUtil() {
    }

    /**
     * 生成订单号：时间戳(13位) + 6位随机数 + 用户ID后4位。
     * <p>时间戳保证单调递增，随数段降低同一毫秒内碰撞概率。</p>
     */
    public static String generateOrderNo(long userId) {
        long ts = System.currentTimeMillis();
        int rand = ThreadLocalRandom.current().nextInt(100000, 1000000);
        return String.format("%d%06d%04d", ts, rand, userId % 10000);
    }

    /**
     * 金额格式化：分转元字符串，规避 BigDecimal 精度问题。
     *
     * @param cents 金额（分）
     */
    public static String centsToYuan(long cents) {
        return String.format("%.2f", cents / 100.0);
    }

    /**
     * 校验字符串是否为纯数字（用于用户名白名单校验等）。
     */
    public static boolean isNumeric(String str) {
        if (str == null || str.isEmpty()) {
            return false;
        }
        return str.chars().allMatch(Character::isDigit);
    }
}