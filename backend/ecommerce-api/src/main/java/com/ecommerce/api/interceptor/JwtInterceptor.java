package com.ecommerce.api.interceptor;

import com.ecommerce.common.context.LoginUser;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.service.util.JwtUtil;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

/**
 * JWT 认证拦截器。
 * <p>职责：放行白名单接口 → 提取 Bearer 令牌 → 校验并写入 ThreadLocal → 请求结束清理。</p>
 * <p>未登录时不在此处抛异常，而是放行到业务层由
 * {@code UserContextHolder.requireUserId()} 统一判定，
 * 这样公开接口（商品列表）不会因为缺令牌而报错。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class JwtInterceptor implements HandlerInterceptor {

    private final JwtUtil jwtUtil;

    /** 令牌请求头 */
    private static final String HEADER = "Authorization";
    private static final String PREFIX = "Bearer ";

    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
        // 预检请求直接放行
        if (HttpMethod.OPTIONS.matches(request.getMethod())) {
            return true;
        }
        // 非 Controller 方法（静态资源等）不参与认证
        if (!(handler instanceof HandlerMethod)) {
            return true;
        }
        String token = resolveToken(request);
        if (StringUtils.hasText(token)) {
            try {
                LoginUser user = jwtUtil.parseToken(token);
                UserContextHolder.set(user);
            } catch (Exception e) {
                // 令牌无效时不清空上下文，让业务层按未登录处理；
                // 公开接口照常可用，需登录的接口返回 401
                log.debug("令牌校验未通过: {}", e.getMessage());
            }
        }
        return true;
    }

    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 必须清理：Tomcat 线程复用，残留会导致下一个请求拿到上一个用户身份
        UserContextHolder.clear();
    }

    private String resolveToken(HttpServletRequest request) {
        String header = request.getHeader(HEADER);
        if (StringUtils.hasText(header) && header.startsWith(PREFIX)) {
            return header.substring(PREFIX.length()).trim();
        }
        // 兼容前端直接放在 token 头的场景
        return request.getHeader("token");
    }
}