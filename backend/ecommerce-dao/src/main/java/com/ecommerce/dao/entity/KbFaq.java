package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 客服问答对。
 *
 * <p>{@code productId} 为 null 表示通用问题（物流售后等），
 * 有值则绑定具体商品。
 */
@Data
@TableName("t_kb_faq")
public class KbFaq implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 关联商品 ID，NULL = 通用问题 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    /** 标准问法 */
    private String question;

    /** 标准答法 */
    private String answer;

    /** 问法关键词，逗号分隔，模糊匹配用 */
    private String questionKw;

    /** 意图：price / stock / spec / compat / warranty / logistics / after_sale */
    private String intent;

    /** 优先级 1 最高 */
    private Integer priority;

    private Integer hitCount;

    private Integer status;
}