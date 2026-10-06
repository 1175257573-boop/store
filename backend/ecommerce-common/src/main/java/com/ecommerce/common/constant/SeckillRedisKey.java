package com.ecommerce.common.constant;

/**
 * 秒杀模块 Redis key 规范。
 *
 * <p>统一前缀 {@code sk:}，所有秒杀 key 都能被
 * {@code redis-cli --scan --pattern "sk:*"} 一把捞出，便于排障和清理。</p>
 */
public final class SeckillRedisKey {

    private static final String PREFIX = "sk:";

    private SeckillRedisKey() {
    }

    /**
     * 库存 key，按桶拆分。
     * <p>分桶目的是打散热点：单个 key 承载全部流量时，
     * Redis 单线程处理同 key 请求是串行的，QPS 会成为瓶颈。
     * 拆成 N 个桶后，不同请求命中不同 key，可并行处理。</p>
     */
    public static String stockKey(Long activityId, Long skuId, int bucketIndex) {
        return PREFIX + "stock:" + activityId + ":" + skuId + ":" + bucketIndex;
    }

    /**
     * 防重 key：同一用户在同一活动同一 SKU 只能参与一次。
     * <p>必须带 TTL，否则进程异常时 key 永不过期，该用户永久无法参与。</p>
     */
    public static String dedupKey(Long activityId, Long skuId, Long userId) {
        return PREFIX + "dedup:" + activityId + ":" + skuId + ":" + userId;
    }

    /** 消费幂等标记：同一预扣流水只允许落一个订单 */
    public static String consumedKey(String preDeductNo) {
        return PREFIX + "consumed:" + preDeductNo;
    }

    /** 活动级前缀（清理缓存时按前缀批量删） */
    public static String activityPrefix(Long activityId) {
        return PREFIX + activityId + ":";
    }

    /** 待下单消息队列 */
    public static String queueKey() {
        return PREFIX + "queue:order";
    }

    /**
     * 处理中队列（消费位点）。
     * <p><b>为什么需要它</b>：用 LPOP 取出即消失，进程崩溃时消息永久丢失，
     * 库存被扣但订单没成；用 LRANGE + LREM 则多个消费者会同时读到同一条消息。
     * {@code RPOPLPUSH} 一次性解决两个问题——原子取走（多消费者不重复）
     * 且保留在处理中队列（崩溃后可恢复）。</p>
     */
    public static String processingQueueKey() {
        return PREFIX + "queue:processing";
    }

    /** 死信队列（消费失败次数超限的消息进这里） */
    public static String deadLetterQueueKey() {
        return PREFIX + "queue:dead";
    }

    /** 活动库存初始化标记，避免重复初始化覆盖已售库存 */
    public static String initFlagKey(Long activityId, Long skuId) {
        return PREFIX + "init:" + activityId + ":" + skuId;
    }

    /* ---------------- 限流相关 ---------------- */

    /**
     * 全局限流 key（令牌桶）。
     * <p>不带活动 ID：全局限流保护的是整个后端容量，
     * 与具体哪个活动无关。</p>
     */
    public static String rateGlobalKey() {
        return PREFIX + "rate:global";
    }

    /** 单用户限流 key */
    public static String rateUserKey(Long activityId, Long userId) {
        return PREFIX + "rate:user:" + activityId + ":" + userId;
    }

    /** 单 IP 限流 key */
    public static String rateIpKey(Long activityId, String ip) {
        return PREFIX + "rate:ip:" + activityId + ":" + ip;
    }

    /** 限流 key 前缀（应急时批量清理） */
    public static String ratePrefix() {
        return PREFIX + "rate:";
    }

    /* ---------------- 监控与降级 ---------------- */

    /**
     * 降级状态标记。
     * <p>Redis 不可用时置位，恢复后清除。用于让所有实例共享同一降级状态，
     * 避免部分实例降级部分实例不降级造成的账目混乱。</p>
     */
    public static String degradeKey() {
        return PREFIX + "degrade";
    }

    /** 消费失败次数计数（超过阈值进死信队列） */
    public static String consumeRetryKey(String preDeductNo) {
        return PREFIX + "retry:" + preDeductNo;
    }
}