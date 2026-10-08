package com.ecommerce.dao.entity;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.databind.annotation.JsonSerialize;
import com.fasterxml.jackson.databind.ser.std.ToStringSerializer;
import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 聊天消息。
 */
@Data
@TableName("t_im_message")
public class ImMessage implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    @JsonSerialize(using = ToStringSerializer.class)
    private Long sessionId;

    /** 发送者ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long fromId;

    /** 0买家 1商家 —— 冗余，省一次查会话表 */
    private Integer fromRole;

    /** 接收者ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long toId;

    private String content;

    /** 1已读 */
    private Integer readFlag;

    private LocalDateTime createTime;
}