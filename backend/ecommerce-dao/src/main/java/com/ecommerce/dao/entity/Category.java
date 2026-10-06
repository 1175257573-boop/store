package com.ecommerce.dao.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 商品分类实体，对应表 t_category。
 */
@Data
@TableName("t_category")
public class Category implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    private String name;

    private String icon;

    /** 排序值，越大越靠前 */
    private Integer sortOrder;

    /** 1启用 0停用 */
    private Integer status;

    private LocalDateTime createTime;
}