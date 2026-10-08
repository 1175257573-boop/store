package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;

/**
 * 商品知识块。
 *
 * <p>智能客服的商品侧知识，一块 = 一个语义自足的事实单元。
 * 分块而不是整段存的原因：整段做向量检索会被多主题平均掉，
 * 用户问「续航多久」时期望命中续航那一段，不是整段里恰好含"续航"二字。
 */
@Data
@TableName("t_kb_product_chunk")
public class KbProductChunk implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 商品 ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 分类 ID（冗余，便于按分类召回） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long categoryId;

    /** 块类型：spec / stock / usecase / warranty */
    private String chunkType;

    /** 块标题，如「核心参数」 */
    private String title;

    /** 块正文，一段完整语义，供 BM25 与向量化共用 */
    private String content;

    /** 关键词，逗号分隔 */
    private String keywords;

    /** 权重 1-5，越大越优先召回 */
    private Integer weight;

    private Integer status;

    public KbProductChunk() {
    }

    public KbProductChunk(Long productId, Long categoryId, String chunkType,
                          String title, String content, String keywords, Integer weight) {
        this.productId = productId;
        this.categoryId = categoryId;
        this.chunkType = chunkType;
        this.title = title;
        this.content = content;
        this.keywords = keywords;
        this.weight = weight;
        this.status = 1;
    }
}