package com.ecommerce.api.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * 密码编码器与安全配置。
 *
 * <p><b>关键点</b>：本项目认证完全走自研的 JWT 拦截器，不需要 Spring Security
 * 的过滤器链。这里只保留 {@link BCryptPasswordEncoder} 用于密码哈希，
 * 并显式放行全部请求。</p>
 *
 * <p>如果不写 {@link SecurityFilterChain} Bean，Spring Boot 会自动装配默认
 * 过滤器链：所有请求都要经过 CSRF 校验、默认拦截所有 URL、并生成随机密码
 * 打日志。表现为前端请求全部 401、且日志里出现
 * {@code Using generated security password}。这里必须显式关掉。</p>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * 全部放行 —— 认证交给 {@code JwtInterceptor}。
     */
    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(csrf -> csrf.disable())            // 前后端分离，无 Cookie 会话，CSRF 无意义
                .cors(cors -> { })                       // 跨域交给 WebMvcConfig 的 addCorsMappings
                .sessionManagement(session -> session
                        .sessionCreationPolicy(org.springframework.security.config.http.SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .anyRequest().permitAll());       // 鉴权由 JWT 拦截器按白名单精细控制
        return http.build();
    }

    /**
     * BCrypt 密码编码器。
     * <p>强度 10：约 100ms/次，兼顾安全与登录响应速度。
     * BCrypt 自带盐值，同一密码每次加密结果不同，因此不能预先算好哈希写进种子数据
     * 再直接比对——必须用 {@code matches()} 校验。</p>
     */
    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder(10);
    }
}
