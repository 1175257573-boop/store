package com.ecommerce.dao.entity;

import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDate;
import java.time.LocalDateTime;

/**
 * 用户实体，对应表 t_user。
 */
@Data
@TableName("t_user")
public class User implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 登录用户名 */
    private String username;

    /** BCrypt 哈希后的密码 */
    private String password;

    /** 昵称 */
    private String nickname;

    private String phone;

    private String email;

    private String avatar;

    /** 0未知 1男 2女 */
    private Integer gender;

    private LocalDate birthday;

    /** 1正常 0禁用 */
    private Integer status;

    /**
     * 角色：0普通用户 1商家 2平台管理员。
     * <p>角色只有三类且固定，单独建角色表只会带来无谓的关联与判定复杂度。
     * 商家能操作哪些数据由 {@code merchantId} 决定，不靠角色再细分。</p>
     */
    private Integer role;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}