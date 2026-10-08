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
     * <p>用 SQL 原子自增而非「查出加一再写回」：
     * 后者在并发发消息时会丢计数。
     *
     * @param toIsBuyer true = 接收方是买家（加 buyer_unread）
     */
    @Update("<script>"
            + "UPDATE t_im_session SET "
            + "  <choose>"
            + "    <when test='toIsBuyer'>buyer_unread = buyer_unread + 1,</when>"
            + "    <otherwise>merchant_unread = merchant_unread + 1,</otherwise>"
            + "  </choose>"
            + "  last_message = #{summary}, last_time = NOW() "
            + "WHERE id = #{sessionId}"
            + "</script>")
    int incrUnread(@Param("sessionId") Long sessionId,
                   @Param("toIsBuyer") boolean toIsBuyer,
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
}
