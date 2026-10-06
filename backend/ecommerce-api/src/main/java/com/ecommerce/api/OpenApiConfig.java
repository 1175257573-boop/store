package com.ecommerce.api;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 接口文档元信息。
 */
@Configuration
public class OpenApiConfig {

    @Bean
    public OpenAPI ecommerceOpenAPI() {
        return new OpenAPI().info(new Info()
                .title("电商平台 API")
                .description("""
                        基于 Spring Boot 3 + MyBatis-Plus + Redis + JWT 的电商平台后端。

                        **鉴权方式**：除白名单接口外，均需在请求头携带令牌
                        ```
                        Authorization: Bearer <token>
                        ```
                        令牌由 `/api/user/login` 签发。
                        """)
                .version("1.0.0")
                .contact(new Contact().name("He Kezhen")));
    }
}