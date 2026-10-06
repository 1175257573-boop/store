package com.ecommerce.service.util;

import com.ecommerce.common.constant.RedisKey;
import com.ecommerce.common.context.LoginUser;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.util.Date;
import java.util.concurrent.TimeUnit;

/**
 * JWT 令牌工具。
 * <p>采用「双保险」设计：签名校验保证令牌未被篡改，Redis 白名单保证登出后令牌立即失效。
 * 若只用 JWT 签名，用户登出后旧令牌在有效期内仍可用，这是纯无状态方案的固有缺陷。</p>
 */
@Slf4j
@Component
public class JwtUtil {

    private final StringRedisTemplate redisTemplate;

    @Value("${ecommerce.jwt.secret}")
    private String secret;

    /** 令牌有效期（秒），默认 7 天 */
    @Value("${ecommerce.jwt.expire:604800}")
    private Long expire;

    public JwtUtil(StringRedisTemplate redisTemplate) {
        this.redisTemplate = redisTemplate;
    }

    private SecretKey buildKey() {
        byte[] keyBytes = secret.getBytes(StandardCharsets.UTF_8);
        // HS256 要求密钥长度 >= 32 字节，不足时直接补齐，避免启动期才报错
        if (keyBytes.length < 32) {
            byte[] padded = new byte[32];
            System.arraycopy(keyBytes, 0, padded, 0, keyBytes.length);
            for (int i = keyBytes.length; i < 32; i++) {
                padded[i] = (byte) ('0' + (i % 10));
            }
            return Keys.hmacShaKeyFor(padded);
        }
        return Keys.hmacShaKeyFor(keyBytes);
    }

    /**
     * 生成令牌并写入 Redis 白名单。
     *
     * @return 令牌字符串
     */
    public String createToken(LoginUser user) {
        Date now = new Date();
        Date expiry = new Date(now.getTime() + expire * 1000);
        String token = Jwts.builder()
                .setSubject(String.valueOf(user.getUserId()))
                .claim("username", user.getUsername())
                .claim("nickname", user.getNickname())
                // 角色与商家ID 放进令牌：Service 层做权限判定时不必回查数据库
                .claim("role", user.getRole())
                .claim("merchantId", user.getMerchantId())
                .issuedAt(now)
                .expiration(expiry)
                .signWith(buildKey())
                .compact();
        // 白名单 TTL 与令牌有效期一致，过期后 Redis 自动回收。
        // Redis 不可用时不能因此让登录失败——令牌本身已签名有效，
        // 只是暂时无法登记白名单，parseToken 会自动降级为仅验签名。
        try {
            redisTemplate.opsForValue().set(
                    RedisKey.TOKEN_WHITELIST + token,
                    String.valueOf(user.getUserId()),
                    expire, TimeUnit.SECONDS);
        } catch (Exception e) {
            log.warn("Redis 不可用，令牌白名单未登记（登录降级）: {}", e.getMessage());
        }
        return token;
    }

    /**
     * 解析令牌，校验签名与有效期。
     *
     * @return 解析出的用户信息
     * @throws BusinessException 令牌无效或过期
     */
    public LoginUser parseToken(String token) {
        // 先验签与有效期（纯计算，不依赖任何外部服务）
        Claims claims;
        try {
            claims = Jwts.parser()
                    .verifyWith(buildKey())
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
        } catch (JwtException | IllegalArgumentException e) {
            // jjwt 对过期令牌抛 ExpiredJwtException（JwtException 子类），统一按过期处理
            if (e instanceof io.jsonwebtoken.ExpiredJwtException) {
                throw new BusinessException(ResultCode.TOKEN_EXPIRED);
            }
            log.warn("JWT 解析失败: {}", e.getMessage());
            throw new BusinessException(ResultCode.TOKEN_INVALID);
        }

        // 签名过了还要查白名单：登出后 Redis 中已删除该 key。
        //
        // 【降级设计】Redis 不可用时<b>跳过白名单校验，只认签名</b>。
        // 理由：白名单的唯一作用是「让登出后的令牌立即失效」，
        // 这是可用性之外的锦上添花；而 Redis 挂掉导致全站不可用
        // 是不可接受的。用短暂的安全性损失换系统可用性是正确的取舍。
        // 代价：Redis 故障期间「已登出的令牌仍能继续用」，故障恢复后自动失效。
        try {
            Boolean exists = redisTemplate.hasKey(RedisKey.TOKEN_WHITELIST + token);
            if (Boolean.FALSE.equals(exists)) {
                throw new BusinessException(ResultCode.TOKEN_INVALID);
            }
        } catch (BusinessException e) {
            throw e;   // 白名单明确不存在，是真的无效令牌
        } catch (Exception e) {
            // 兜底：Redis 客户端各种异常（含超时）都降级为仅验签名。
            // 关键是不能让异常冒到拦截器——那样整个系统会被 Redis 拖死。
            log.warn("Redis 不可用，JWT 降级为仅验签名（登出状态暂不生效）: {}",
                    e.getClass().getSimpleName());
        }

        // 兼容旧令牌：role/merchantId 可能不存在，此时按普通用户处理
        Integer role = claims.get("role", Integer.class);
        Long merchantId = claims.get("merchantId", Long.class);
        return new LoginUser(
                Long.valueOf(claims.getSubject()),
                claims.get("username", String.class),
                claims.get("nickname", String.class),
                role,
                merchantId);
    }

    /** 登出：从白名单移除令牌 */
    public void revokeToken(String token) {
        redisTemplate.delete(RedisKey.TOKEN_WHITELIST + token);
    }

    /**
     * 续期：把白名单 TTL 重置为完整有效期，用于活跃用户自动续期。
     */
    public void renew(String token) {
        redisTemplate.expire(RedisKey.TOKEN_WHITELIST + token, expire, TimeUnit.SECONDS);
    }

    public Long getExpire() {
        return expire;
    }
}