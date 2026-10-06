package com.ecommerce.api.config;

import com.ecommerce.api.interceptor.JwtInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * 拦截器注册。
 * <p>白名单列出所有无需登录即可访问的接口路径，
 * 逐一列出比通配放行更安全，新增接口时能明确意识到是否需要登录。</p>
 */
@Configuration
@RequiredArgsConstructor
public class InterceptorConfig implements WebMvcConfigurer {

    private final JwtInterceptor jwtInterceptor;

    /** 无需登录的接口 */
    private static final String[] WHITE_LIST = {
            "/api/user/login",
            "/api/user/register",
            "/api/product/**",
            "/api/category/**",
            "/doc.html",
            "/webjars/**",
            "/v3/api-docs/**",
            "/swagger-ui/**",
            "/actuator/**",
            "/error"
    };

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(jwtInterceptor)
                .addPathPatterns("/api/**")
                .excludePathPatterns(WHITE_LIST);
    }
}