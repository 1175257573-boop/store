package com.ecommerce.service.util;

import com.alibaba.fastjson2.JSON;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * Redis 缓存工具，屏蔽「序列化 / 反序列化 / 缓存穿透」等重复代码。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RedisCacheUtil {

    private final StringRedisTemplate redisTemplate;

    /** 默认缓存过期时间：30 分钟 */
    public static final Duration DEFAULT_TTL = Duration.ofMinutes(30);

    /** 查缓存 -> 命中则返回；未命中执行 loader 并回写 */
    public <T> T getOrLoad(String key, Class<T> type, java.util.function.Supplier<T> loader) {
        return getOrLoad(key, type, loader, DEFAULT_TTL);
    }

    public <T> T getOrLoad(String key, Class<T> type, java.util.function.Supplier<T> loader, Duration ttl) {
        String json = redisTemplate.opsForValue().get(key);
        if (json != null && !json.isEmpty()) {
            try {
                return JSON.parseObject(json, type);
            } catch (Exception e) {
                // 缓存数据格式异常（升级导致结构变化）时删除并回源，不让脏数据卡住请求
                log.warn("缓存反序列化失败，已清除 key={}: {}", key, e.getMessage());
                redisTemplate.delete(key);
            }
        }
        T value = loader.get();
        if (value != null) {
            set(key, value, ttl);
        }
        return value;
    }

    /** 写入缓存 */
    public void set(String key, Object value, Duration ttl) {
        try {
            redisTemplate.opsForValue().set(key, JSON.toJSONString(value), ttl.toSeconds(), TimeUnit.SECONDS);
        } catch (Exception e) {
            // 缓存写失败不能影响主流程
            log.warn("缓存写入失败 key={}: {}", key, e.getMessage());
        }
    }

    /** 删除缓存 */
    public void delete(String key) {
        try {
            redisTemplate.delete(key);
        } catch (Exception e) {
            log.warn("缓存删除失败 key={}: {}", key, e.getMessage());
        }
    }

    /** 按前缀批量删除（用于分类变更后清理商品列表缓存） */
    public void deleteByPrefix(String prefix) {
        try {
            var keys = redisTemplate.keys(prefix + "*");
            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
            }
        } catch (Exception e) {
            log.warn("批量删除缓存失败 prefix={}: {}", prefix, e.getMessage());
        }
    }

    /** 生成 int 类型 key（购物车数量统计等简单计数场景） */
    public Integer getInteger(String key) {
        String v = redisTemplate.opsForValue().get(key);
        return v == null ? 0 : Integer.parseInt(v);
    }
}