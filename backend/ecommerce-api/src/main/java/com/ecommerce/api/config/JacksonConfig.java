package com.ecommerce.api.config;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateSerializer;
import com.fasterxml.jackson.datatype.jsr310.ser.LocalDateTimeSerializer;
import org.springframework.boot.autoconfigure.jackson.Jackson2ObjectMapperBuilderCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/**
 * Jackson 全局配置。
 *
 * <p><b>关于 Long 转字符串</b>：JS 的 Number 最大安全整数是 2^53-1，
 * ID 一旦超出该范围前端会丢精度（末位变 0）。但这里<b>刻意不做全局转换</b>：
 * 全局 {@code ToStringSerializer} 会把分页的 {@code total}、计数字段等
 * 一并转成字符串，导致前端 {@code total === 5} 这类数值比较恒为 false。</p>
 *
 * <p>改用<b>字段级注解</b>：在所有 ID 字段上标
 * {@code @JsonSerialize(using = ToStringSerializer.class)}，
 * 语义清晰且不影响数值语义。实体类中已统一加好。</p>
 */
@Configuration
public class JacksonConfig {

    public static final String DATE_TIME_PATTERN = "yyyy-MM-dd HH:mm:ss";
    public static final String DATE_PATTERN = "yyyy-MM-dd";

    @Bean
    public Jackson2ObjectMapperBuilderCustomizer jacksonCustomizer() {
        return (Jackson2ObjectMapperBuilder builder) -> {
            DateTimeFormatter dtFormatter = DateTimeFormatter.ofPattern(DATE_TIME_PATTERN);
            DateTimeFormatter dFormatter = DateTimeFormatter.ofPattern(DATE_PATTERN);

            JavaTimeModule javaTimeModule = new JavaTimeModule();
            javaTimeModule.addSerializer(LocalDateTime.class, new LocalDateTimeSerializer(dtFormatter));
            javaTimeModule.addSerializer(LocalDate.class, new LocalDateSerializer(dFormatter));

            builder.modules(javaTimeModule);
            // null 字段不输出，减小响应体积
            builder.serializationInclusion(JsonInclude.Include.NON_NULL);
            builder.featuresToDisable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        };
    }
}