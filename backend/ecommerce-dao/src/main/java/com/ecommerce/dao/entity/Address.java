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
 * 收货地址实体，对应表 t_address。
 */
@Data
@TableName("t_address")
public class Address implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long userId;

    private String receiver;

    private String phone;

    private String province;

    private String city;

    private String district;

    /** 详细地址 */
    private String detail;

    /** 是否默认 1是 0否 */
    private Integer isDefault;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}