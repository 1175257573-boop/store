package com.ecommerce.api;

import org.mybatis.spring.annotation.MapperScan;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 电商平台后端启动类。
 *
 * <p>{@code @MapperScan} 统一扫描 dao 模块的 Mapper，接口层无需逐个加 @Mapper。</p>
 * <p>{@code @EnableScheduling} 开启定时任务，秒杀模块的补偿与对账依赖它。</p>
 */
@SpringBootApplication(scanBasePackages = "com.ecommerce")
@MapperScan("com.ecommerce.dao.mapper")
@EnableScheduling
public class EcommerceApplication {

    public static void main(String[] args) {
        SpringApplication.run(EcommerceApplication.class, args);
        System.out.println("""

                ====================================================
                  电商平台后端启动成功
                  接口文档: http://localhost:8080/doc.html
                  健康检查: http://localhost:8080/actuator/health
                ====================================================
                """);
    }
}