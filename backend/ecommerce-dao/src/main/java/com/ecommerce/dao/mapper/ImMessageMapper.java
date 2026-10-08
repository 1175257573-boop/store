package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.ImMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * 消息 Mapper。
 */
@Mapper
public interface ImMessageMapper extends BaseMapper<ImMessage> {

    /**
     * 商家侧未读总数 —— 用于商家端顶部红点。
     * <b>必须按 merchantId 过滤</b>，否则商家能看到全平台未读数。
     */
    @Select("SELECT COALESCE(SUM(merchant_unread), 0) FROM t_im_session "
            + "WHERE merchant_id = #{merchantId} AND status != 3")
    int sumMerchantUnread(@Param("merchantId") Long merchantId);

    /**
     * 买家侧未读总数。
     */
    @Select("SELECT COALESCE(SUM(buyer_unread), 0) FROM t_im_session "
            + "WHERE buyer_id = #{buyerId} AND status != 2")
    int sumBuyerUnread(@Param("buyerId") Long buyerId);

    /**
     * 发送消息后：刷新会话摘要/时间，并给接收方 +1 未读。
     *
     * <p>接收方判断用 <b>角色参数</b> 而不是「比对 toId 与 buyer_id」——
     * 后者写不出「到底是买家还是商家该加未读」的分支，
     * 且容易写成 SQL 原子自增时判断不清。
     *
     /**
     * 发送消息后：刷新会话摘要 + 给接收方 +1 未读。
     *
     * <p>拆成两个方法而不是一个带 {@code <choose>} 的 ——
     * 实测 {@code @Update} 里的 {@code <choose>} 在本项目会抛 500，
     * 表现为「消息写进库了但接口报错」，极难排查。
     *
     * <p>用 SQL 原子自增而非「查出加一再写回」：
     * 后者在并发发消息时会丢计数。
     *
     * @param toIsBuyer true = 接收方是买家（加 buyer_unread）
     */
    @Update("UPDATE t_im_session SET buyer_unread = buyer_unread + 1, "
            + "last_message = #{summary}, last_time = NOW() "
            + "WHERE id = #{sessionId}")
    int incrBuyerUnread(@Param("sessionId") Long sessionId,
                        @Param("summary") String summary);

    @Update("UPDATE t_im_session SET merchant_unread = merchant_unread + 1, "
            + "last_message = #{summary}, last_time = NOW() "
            + "WHERE id = #{sessionId}")
    int incrMerchantUnread(@Param("sessionId") Long sessionId,
                           @Param("summary") String summary);

    /**
     * 清零买家侧未读。
     */
    @Update("UPDATE t_im_session SET buyer_unread = 0 "
            + "WHERE id = #{sessionId} AND buyer_id = #{buyerId}")
    int clearBuyerUnread(@Param("sessionId") Long sessionId,
                         @Param("buyerId") Long buyerId);

    /**
     * 清零商家侧未读。
     */
    @Update("UPDATE t_im_session SET merchant_unread = 0 "
            + "WHERE id = #{sessionId} AND merchant_id = #{merchantId}")
    int clearMerchantUnread(@Param("sessionId") Long sessionId,
                            @Param("merchantId") Long merchantId);

    /**
     * 把会话内发给某人的消息标记已读。
     *
     * <p>只按 {@code session_id + to_id + read_flag=0} 过滤，
     * 不接受前端传入的 fromId —— 那样就能标记别人的消息已读。
     */
    @Update("UPDATE t_im_message SET read_flag = 1 "
            + "WHERE session_id = #{sessionId} AND to_id = #{userId} AND read_flag = 0")
    int markRead(@Param("sessionId") Long sessionId, @Param("userId") Long userId);

    /**
     * 批量标记已读 —— 买家侧：清「商家发给我」的未读。
     *
     * <p><b>为什么不用 {@code <choose>} 动态标签</b>：
     * {@code @Update} 注解**不支持** {@code <choose>/<when>}，
     * 只有 {@code <script>} 包裹的动态 SQL 才支持，
     * 且一个方法只能返回单条语句的更新数。
     * 拆成两个方法反而更直白 —— 各自的 SQL 一眼看得懂。
     *
     * <p>消息状态与未读计数一起清，避免两者不一致。
     */
    @Update("UPDATE t_im_message SET read_flag = 1 "
            + "WHERE to_id = #{buyerId} AND read_flag = 0 "
            + "AND session_id IN (SELECT id FROM t_im_session WHERE buyer_id = #{buyerId})")
    int markAllReadAsBuyer(@Param("buyerId") Long buyerId);

    @Update("UPDATE t_im_session SET buyer_unread = 0 WHERE buyer_id = #{buyerId}")
    int clearBuyerUnreadAll(@Param("buyerId") Long buyerId);

    /** 批量标记已读 —— 商家侧：清「买家发给我」的未读。 */
    @Update("UPDATE t_im_message SET read_flag = 1 "
            + "WHERE to_id = #{userId} AND read_flag = 0 "
            + "AND session_id IN (SELECT id FROM t_im_session WHERE merchant_id = #{merchantId})")
    int markAllReadAsMerchant(@Param("userId") Long userId,
                              @Param("merchantId") Long merchantId);

    @Update("UPDATE t_im_session SET merchant_unread = 0 WHERE merchant_id = #{merchantId}")
    int clearMerchantUnreadAll(@Param("merchantId") Long merchantId);
}
