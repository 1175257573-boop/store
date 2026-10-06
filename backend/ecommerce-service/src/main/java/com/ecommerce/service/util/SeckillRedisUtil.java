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
 * 秒杀 Redis 库存操作。
 *
 * <p><b>所有涉及「判断 + 修改」的逻辑都必须放在 Lua 脚本里</b>。
 * 原因：Redis 的单条命令是原子的，但多条命令之间存在间隙。
 * 写成 {@code GET} 然后 {@code DECRBY}，两个命令之间另一个请求可能插进来读到旧值，
 * 于是「检查通过 → 实际扣减时已无货」的超卖窗口就出现了。
 * Lua 脚本在 Redis 中整体原子执行，从根本上消除这个窗口。</p>
 */
@Slf4j
@Component
public class SeckillRedisUtil {

    private final StringRedisTemplate redisTemplate;

    /**
     * 库存预扣脚本。
     * <p>逻辑：先取分桶库存 → 够则扣减并返回 1，不够返回 0，异常返回 -1。</p>
     */
    private static final String LUA_DEDUCT = """
            local stock = redis.call('GET', KEYS[1])
            if stock == false then
              return -1
            end
            stock = tonumber(stock)
            local qty = tonumber(ARGV[1])
            if stock == nil or stock < qty then
              return 0
            end
            redis.call('DECRBY', KEYS[1], qty)
            return 1
            """;

    /**
     * 库存回补脚本。
     * <p>用 LEAST 兜底补到初始容量，重复回补时最多补满，不会超发。</p>
     */
    private static final String LUA_RESTORE = """
            local stock = redis.call('GET', KEYS[1])
            if stock == false then
              return -1
            end
            local capacity = tonumber(ARGV[2])
            local qty = tonumber(ARGV[1])
            local newStock = tonumber(stock) + qty
            if newStock > capacity then
              newStock = capacity
            end
            redis.call('SET', KEYS[1], newStock)
            return newStock
            """;

    /** 防重占位脚本：SET NX EX，原子完成「检查 + 占位 + 设置过期」 */
    private static final String LUA_DEDUP = """
            local setResult = redis.call('SET', KEYS[1], ARGV[1], 'NX', 'EX', ARGV[2])
            if setResult then
              return 1
            end
            return 0
            """;

    /** 消费幂等标记脚本 */
    private static final String LUA_CONSUMED = """
            local setResult = redis.call('SET', KEYS[1], '1', 'NX', 'EX', ARGV[1])
            if setResult then
              return 1
            end
            return 0
            """;

    private final RedisScript<Long> deductScript;
    private final RedisScript<Long> restoreScript;
    private final RedisScript<Long> dedupScript;
    private final RedisScript<Long> consumedScript;

    /** 库存分桶默认数量 */
    @Value("${ecommerce.seckill.bucket-count:10}")
    private int defaultBucketCount;

    public SeckillRedisUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
        this.deductScript = new DefaultRedisScript<>(LUA_DEDUCT, Long.class);
        this.restoreScript = new DefaultRedisScript<>(LUA_RESTORE, Long.class);
        this.dedupScript = new DefaultRedisScript<>(LUA_DEDUP, Long.class);
        this.consumedScript = new DefaultRedisScript<>(LUA_CONSUMED, Long.class);
    }

    /**
     * 初始化库存到 Redis 分桶。
     * <p>把 totalStock 平均拆成 bucketCount 个桶，规避单 key 热点。
     * 余数分给前几个桶，保证各桶之和精确等于 totalStock。</p>
     */
    public void initStock(Long activityId, Long skuId, int totalStock, int bucketCount) {
        int buckets = Math.max(bucketCount <= 0 ? defaultBucketCount : bucketCount, 1);
        int base = totalStock / buckets;
        int remainder = totalStock % buckets;
        for (int i = 0; i < buckets; i++) {
            int qty = base + (i < remainder ? 1 : 0);
            redisTemplate.opsForValue().set(
                    SeckillRedisKey.stockKey(activityId, skuId, i), String.valueOf(qty));
        }
        log.info("秒杀库存初始化: activity={}, sku={}, total={}, buckets={}",
                activityId, skuId, totalStock, buckets);
    }

    /**
     * 从指定桶预扣库存。
     *
     * @return 1=成功，0=库存不足，-1=key 不存在（需重新初始化）
     */
    public long deductStock(Long activityId, Long skuId, int bucketIndex, int qty) {
        String key = SeckillRedisKey.stockKey(activityId, skuId, bucketIndex);
        return (long) redisTemplate.execute(deductScript,
                Collections.singletonList(key), String.valueOf(qty));
    }

    /**
     * 回补指定桶库存。
     */
    public long restoreStock(Long activityId, Long skuId, int bucketIndex,
                             int qty, int capacity) {
        String key = SeckillRedisKey.stockKey(activityId, skuId, bucketIndex);
        return (long) redisTemplate.execute(restoreScript,
                Collections.singletonList(key),
                String.valueOf(qty), String.valueOf(capacity));
    }

    /**
     * 查某桶剩余库存。
     */
    public Integer getStock(Long activityId, Long skuId, int bucketIndex) {
        String v = redisTemplate.opsForValue().get(
                SeckillRedisKey.stockKey(activityId, skuId, bucketIndex));
        return v == null ? null : Integer.parseInt(v);
    }

    /**
     * 查所有桶的剩余库存之和（对账用）。
     */
    public int totalRedisStock(Long activityId, Long skuId, int bucketCount) {
        int sum = 0;
        for (int i = 0; i < bucketCount; i++) {
            Integer v = getStock(activityId, skuId, i);
            if (v != null) {
                sum += v;
            }
        }
        return sum;
    }

    /**
     * 请求防重占位。
     * <p>用 SET NX EX 一次完成检查 + 占位 + 设过期。
     * 写 {@code SETNX} 再 {@code EXPIRE} 分两步会有一个窗口：
     * 此刻 key 已存在但无过期时间，若进程崩溃该 key 永不过期，
     * 之后该用户永远无法参与秒杀。</p>
     *
     * @return true=占位成功（首次请求），false=已存在（重复请求）
     */
    public boolean tryDedup(Long activityId, Long skuId, Long userId, int ttlSeconds) {
        String key = SeckillRedisKey.dedupKey(activityId, skuId, userId);
        long result = (long) redisTemplate.execute(dedupScript,
                Collections.singletonList(key), "1", String.valueOf(ttlSeconds));
        return result == 1L;
    }

    /**
     * 消费幂等标记：标记某条消息已被消费。
     *
     * @return true=首次消费，false=已消费过（重复投递）
     */
    public boolean tryMarkConsumed(String preDeductNo, int ttlSeconds) {
        String key = SeckillRedisKey.consumedKey(preDeductNo);
        long result = (long) redisTemplate.execute(consumedScript,
                Collections.singletonList(key), String.valueOf(ttlSeconds));
        return result == 1L;
    }

    /** 释放防重占位（后续步骤失败时调用，允许用户重试） */
    public void releaseDedup(Long activityId, Long skuId, Long userId) {
        redisTemplate.delete(SeckillRedisKey.dedupKey(activityId, skuId, userId));
    }

    /**
     * 清理某活动的所有秒杀缓存（测试重置用）。
     * <p>注意 key 格式：分桶 key 是 {@code sk:stock:活动:sku:桶号}，
     * 防重 key 是 {@code sk:dedup:活动:sku:用户}，
     * 活动 ID 不在固定位置上，所以要用模式匹配而不是前缀匹配。</p>
     */
    public void clearActivity(Long activityId) {
        var patterns = List.of(
                "sk:stock:" + activityId + ":*",
                "sk:dedup:" + activityId + ":*",
                "sk:init:" + activityId + ":*");
        int deleted = 0;
        for (String pattern : patterns) {
            var keys = redisTemplate.keys(pattern);
            if (keys != null && !keys.isEmpty()) {
                // delete(Collection) 返回 Long 类型，拆箱前判空避免 NPE
                Long n = redisTemplate.delete(keys);
                deleted += n == null ? 0 : n.intValue();
            }
        }
        log.info("清理秒杀缓存: activity={}, 清除 {} 个 key", activityId, deleted);
    }

    /** 查队列长度（监控用） */
    public long queueLength(String queueKey) {
        Long len = redisTemplate.opsForList().size(queueKey);
        return len == null ? 0L : len;
    }

    /**
     * 原子取出一条消息并移入处理中队列。
     *
     * <p><b>用 RPOPLPUSH 而非 LPOP / LRANGE+LREM 的原因</b>：</p>
     * <ul>
     *   <li>LPOP：取出即消失，进程崩溃时消息永久丢失 → 库存被扣但订单没成</li>
     *   <li>LRANGE + LREM：多个消费者同时读到同一条消息 → 大量无效重复消费
     *       （靠幂等兜住不超卖，但白白浪费 CPU 和 DB 连接）</li>
     *   <li>RPOPLPUSH：原子操作，多消费者各取各的；消息同时留在处理中队列，
     *       崩溃后{@link #recoverStaleMessages} 能把它捞回来</li>
     * </ul>
     *
     * @return 消息内容，队列为空时返回 null
     */
    public String takeMessage(String queueKey, String processingKey) {
        Object v = redisTemplate.opsForList().rightPopAndLeftPush(queueKey, processingKey);
        return v == null ? null : v.toString();
    }

    /**
     * 确认消费完成：从处理中队列移除。
     *
     * @return 实际移除条数（0 表示消息不在队列里，可能已被恢复任务捞走）
     */
    public long ackMessage(String processingKey, String message) {
        Long n = redisTemplate.opsForList().remove(processingKey, 1, message);
        return n == null ? 0L : n;
    }

    /**
     * 消费失败：放回主队列等待重试。
     */
    public void nackMessage(String queueKey, String processingKey, String message) {
        redisTemplate.opsForList().remove(processingKey, 1, message);
        redisTemplate.opsForList().leftPush(queueKey, message);
    }

    /**
     * 移入死信队列。
     * <p>重试次数超限的消息不能无限重试，否则会阻塞整个队列
     * （FIFO 语义下队头一条坏消息能把后面全堵住）。</p>
     */
    public void moveToDeadLetter(String processingKey, String deadKey, String message) {
        redisTemplate.opsForList().remove(processingKey, 1, message);
        redisTemplate.opsForList().leftPush(deadKey, message);
    }

    /**
     * 恢复僵死消息：把处理中队列里的消息扫回主队列。
     *
     * <p><b>这是异步架构的必备兜底</b>：消费者在「已取走未 ack」时崩溃，
     * 消息会永久卡在处理中队列里。定时调用本方法即可恢复。</p>
     *
     * @param limit 单次恢复上限
     * @return 恢复的消息数
     */
    public int recoverStaleMessages(String processingKey, String queueKey, int limit) {
        var messages = redisTemplate.opsForList().range(processingKey, 0, limit - 1L);
        if (messages == null || messages.isEmpty()) {
            return 0;
        }
        int n = 0;
        for (String msg : messages) {
            Long removed = redisTemplate.opsForList().remove(processingKey, 1, msg);
            if (removed != null && removed > 0) {
                redisTemplate.opsForList().leftPush(queueKey, msg);
                n++;
            }
        }
        if (n > 0) {
            log.warn("恢复僵死消息 {} 条回主队列", n);
        }
        return n;
    }

    /** 死信队列长度（监控用） */
    public long deadLetterCount() {
        return queueLength(SeckillRedisKey.deadLetterQueueKey());
    }
}
