package com.ecommerce.service.util;

import com.ecommerce.common.constant.SeckillRedisKey;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.List;

/**
 * 秒杀限流器。
 *
 * <p><b>限流解决的是「不该进到库存逻辑的请求」</b>，不是防超卖。
 * 10 万 QPS 打进来，只要库存扣减是原子的就不会超卖——但数据库、
 * 线程池、网络都会被这些「注定要失败」的请求白白消耗掉。
 * 限流把无效流量挡在最外层，是整个链路的第一道防线。</p>
 *
 * <p>三个维度叠加，各管一件事：</p>
 * <ul>
 *   <li><b>全局 QPS</b>：保护后端整体容量，防止单机被打垮</li>
 *   <li><b>单用户频率</b>：防止单个账号用脚本刷接口</li>
 *   <li><b>单 IP 频率</b>：防止代理池批量刷单</li>
 * </ul>
 *
 * <p>全部用 Lua 实现，保证「计数 + 设置过期」是原子的。
 * 写成 INCR 再 EXPIRE 分两步会有窗口：此刻计数已存在但无 TTL，
 * 该 key 永不过期，内存泄漏且该维度从此被永久封禁。</p>
 */
@Slf4j
@Component
public class SeckillRateLimiter {

    private final StringRedisTemplate redisTemplate;

    /**
     * 滑动窗口限流脚本（ZSET 实现）。
     * <p>相比固定窗口计数器，滑动窗口不会在窗口边界出现
     * 「2 倍流量通过」的问题：固定窗口在 [59s,60s] 和 [60s,61s] 各能放
     * 满额，实际 2 秒内通过了 2 倍配额。</p>
     */
    private static final String LUA_SLIDING_WINDOW = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])          -- 当前时间戳（毫秒）
            local window = tonumber(ARGV[2])       -- 窗口大小（毫秒）
            local limit = tonumber(ARGV[3])        -- 窗口内最大请求数
            local member = ARGV[4]                 -- 请求唯一标识

            -- 清理窗口外的记录
            redis.call('ZREMRANGEBYSCORE', key, 0, now - window)
            -- 统计窗口内当前记录数
            local count = redis.call('ZCARD', key)
            if count >= limit then
              return 0
            end
            -- 记录本次请求
            redis.call('ZADD', key, now, member)
            -- 设置过期时间（窗口的 2 倍，足够覆盖边界）
            redis.call('PEXPIRE', key, window * 2)
            return 1
            """;

    /** 令牌桶脚本：支持突发流量 */
    private static final String LUA_TOKEN_BUCKET = """
            local key = KEYS[1]
            local now = tonumber(ARGV[1])           -- 当前时间戳（毫秒）
            local rate = tonumber(ARGV[2])          -- 每毫秒生成令牌数
            local capacity = tonumber(ARGV[3])      -- 桶容量（允许突发上限）
            local want = tonumber(ARGV[4])          -- 本次需要的令牌数

            local data = redis.call('HMGET', key, 'tokens', 'last')
            local tokens = tonumber(data[1])
            local last = tonumber(data[2])
            if tokens == nil then
              tokens = capacity
              last = now
            end
            -- 按经过时间补充令牌，上限为桶容量
            local delta = math.max(0, now - last)
            tokens = math.min(capacity, tokens + delta * rate)
            if tokens < want then
              redis.call('HSET', key, 'tokens', tokens, 'last', now)
              return 0
            end
            tokens = tokens - want
            redis.call('HSET', key, 'tokens', tokens, 'last', now)
            redis.call('PEXPIRE', key, math.ceil(capacity / rate) + 1000)
            return 1
            """;

    private final RedisScript<Long> slidingWindowScript;
    private final RedisScript<Long> tokenBucketScript;

    // ===== 全局限流配置 =====
    /** 全局每秒允许通过的请求数 */
    @Value("${ecommerce.seckill.rate-limit.global-qps:2000}")
    private int globalQps;

    /** 单用户每秒最多请求数 */
    @Value("${ecommerce.seckill.rate-limit.user-qps:1}")
    private int userQps;

    /** 单 IP 每秒最多请求数 */
    @Value("${ecommerce.seckill.rate-limit.ip-qps:5}")
    private int ipQps;

    /** 限流总开关（应急时可快速关闭限流） */
    @Value("${ecommerce.seckill.rate-limit.enabled:true}")
    private boolean rateLimitEnabled;

    public SeckillRateLimiter(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.slidingWindowScript = new DefaultRedisScript<>(LUA_SLIDING_WINDOW, Long.class);
        this.tokenBucketScript = new DefaultRedisScript<>(LUA_TOKEN_BUCKET, Long.class);
    }

    /**
     * 滑动窗口限流。
     *
     * @return true=放行，false=被限流
     */
    public boolean tryAcquire(String key, int limit, int windowMillis, String member) {
        if (!rateLimitEnabled) {
            return true;
        }
        try {
            Long r = redisTemplate.execute(slidingWindowScript,
                    Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(windowMillis),
                    String.valueOf(limit),
                    member);
            return r != null && r == 1L;
        } catch (Exception e) {
            // Redis 故障时限流器必须「失败放行」而不是「失败拒绝」——
            // 否则 Redis 一挂，所有请求全被拦下，系统直接不可用。
            // 正确性由 DB 条件更新兜底，限流只是性能优化。
            log.warn("限流器异常，降级为放行: {}", e.getMessage());
            return true;
        }
    }

    /**
     * 令牌桶限流（支持突发）。
     * <p>滑动窗口严格控制速率，令牌桶允许在桶满时一次性放行 capacity 个请求。
     * 秒杀场景常用令牌桶：用户手速快、点击间隔短，滑动窗口容易误杀正常用户。</p>
     *
     * @param key      限流 key
     * @param qps      每秒生成令牌数
     * @param capacity 桶容量（突发上限）
     */
    public boolean tryAcquireToken(String key, int qps, int capacity) {
        if (!rateLimitEnabled) {
            return true;
        }
        try {
            Long r = redisTemplate.execute(tokenBucketScript,
                    Collections.singletonList(key),
                    String.valueOf(System.currentTimeMillis()),
                    String.valueOf(qps / 1000.0),   // 转为每毫秒生成速率
                    String.valueOf(capacity),
                    "1");
            return r != null && r == 1L;
        } catch (Exception e) {
            log.warn("令牌桶异常，降级为放行: {}", e.getMessage());
            return true;
        }
    }

    /** 全局 QPS 限流（令牌桶，允许突发） */
    public boolean tryGlobal() {
        return tryAcquireToken(SeckillRedisKey.rateGlobalKey(), globalQps, globalQps);
    }

    /** 单用户频率限流（滑动窗口，严格） */
    public boolean tryUser(Long activityId, Long userId) {
        return tryAcquire(
                SeckillRedisKey.rateUserKey(activityId, userId), userQps, 1000,
                userId + "-" + System.nanoTime());
    }

    /** 单 IP 频率限流（滑动窗口） */
    public boolean tryIp(Long activityId, String ip) {
        return tryAcquire(
                SeckillRedisKey.rateIpKey(activityId, ip), ipQps, 1000,
                ip + "-" + System.nanoTime());
    }

    /**
     * 一站式检查：全局 → 用户 → IP。
     *
     * @return null 表示放行，返回字符串表示被哪一层限流
     */
    public String check(Long activityId, Long userId, String ip) {
        if (!tryGlobal()) {
            return "GLOBAL";
        }
        if (!tryUser(activityId, userId)) {
            return "USER";
        }
        if (ip != null && !tryIp(activityId, ip)) {
            return "IP";
        }
        return null;
    }

    /** 取限流配置，供管理接口展示 */
    public List<Integer> getConfig() {
        return List.of(globalQps, userQps, ipQps, rateLimitEnabled ? 1 : 0);
    }
}