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
 * 买家-店铺会话。
 *
 * <p><b>会话按「买家 × 店铺」唯一，不按商品。</b>
 * 买家问「这能退吗」后应该能继续问「那台呢」——
 * 若按商品建会话，换商品就断了。
 * 唯一键 {@code uk_buyer_merchant} 保证一对一会话不重复创建。
 */
@Data
@TableName("t_im_session")
public class ImSession implements Serializable {

    @TableId(type = IdType.AUTO)
    @JsonSerialize(using = ToStringSerializer.class)
    private Long id;

    /** 买家用户ID */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long buyerId;

    /** 店铺ID —— 会话归属依据 */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long merchantId;

    /** 关联商品（从商品页发起时带入，仅作来源标记） */
    @JsonSerialize(using = ToStringSerializer.class)
    private Long productId;

    /** 1正常 2买家删除 3商家删除 */
    private Integer status;

    /** 买家未读数（与商家未读方向不同，必须分开） */
    private Integer buyerUnread;

    /** 商家未读数 */
    private Integer merchantUnread;

    /** 最后一条消息摘要（会话列表展示，避免 join 消息表） */
    private String lastMessage;

    /** 最后消息时间（列表排序） */
    private LocalDateTime lastTime;

    private LocalDateTime createTime;

    private LocalDateTime updateTime;
}