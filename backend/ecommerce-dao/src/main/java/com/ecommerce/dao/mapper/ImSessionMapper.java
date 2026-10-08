package com.ecommerce.dao.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.ecommerce.dao.entity.ImMessage;
import com.ecommerce.dao.entity.ImSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;

/**
 * 会话 Mapper。
 *
 * <p><b>权限红线</b>：所有查询/更新都必须把「当前身份」写进 WHERE 条件，
 * 不能只靠 service 层先查再判断 —— 那是两步操作，中间存在竞态。
 */
@Mapper
public interface ImSessionMapper extends BaseMapper<ImSession> {

    /**
     * 查会话，<b>强制带上买家或店铺的归属校验</b>。
     *
     * @param buyerId    买家ID（非管理员必传）
     * @param merchantId 店铺ID（商家或管理员必传）
     * @return 命中则返回会话，未命中说明无权访问
     */
    @Select("<script>"
            + "SELECT * FROM t_im_session "
            + "WHERE 1=1 "
            + "<if test='buyerId != null'> AND buyer_id = #{buyerId} </if>"
            + "<if test='merchantId != null'> AND merchant_id = #{merchantId} </if>"
            + "LIMIT 1"
            + "</script>")
    ImSession selectByParticipants(@Param("buyerId") Long buyerId,
                                   @Param("merchantId") Long merchantId);

    /**
     * 买家的会话列表。
     */
    @Select("SELECT s.*, m.shop_name AS shop_name, u.nickname AS buyer_nickname "
            + "FROM t_im_session s "
            + "LEFT JOIN t_merchant m ON m.id = s.merchant_id "
            + "LEFT JOIN t_user u ON u.id = s.buyer_id "
            + "WHERE s.buyer_id = #{buyerId} AND s.status != 2 "
            + "ORDER BY s.last_time DESC, s.id DESC LIMIT #{offset}, #{limit}")
    List<ImSessionVO> listBuyerSessions(@Param("buyerId") Long buyerId,
                                       @Param("offset") int offset,
                                       @Param("limit") int limit);

    /**
     * 商家的会话列表 —— <b>强制按自己的店铺过滤</b>，
     * 不接受前端传来的 merchantId（否则可越权看别家买家的会话）。
     */
    @Select("SELECT s.*, m.shop_name AS shop_name, u.nickname AS buyer_nickname "
            + "FROM t_im_session s "
            + "LEFT JOIN t_merchant m ON m.id = s.merchant_id "
            + "LEFT JOIN t_user u ON u.id = s.buyer_id "
            + "WHERE s.merchant_id = #{merchantId} AND s.status != 3 "
            + "ORDER BY s.last_time DESC, s.id DESC LIMIT #{offset}, #{limit}")
    List<ImSessionVO> listMerchantSessions(@Param("merchantId") Long merchantId,
                                           @Param("offset") int offset,
                                           @Param("limit") int limit);

    /**
     * 管理员：全部会话（纠纷排查用，只读）。
     */
    @Select("SELECT s.*, m.shop_name AS shop_name, u.nickname AS buyer_nickname "
            + "FROM t_im_session s "
            + "LEFT JOIN t_merchant m ON m.id = s.merchant_id "
            + "LEFT JOIN t_user u ON u.id = s.buyer_id "
            + "WHERE s.status = 1 "
            + "ORDER BY s.last_time DESC LIMIT #{offset}, #{limit}")
    List<ImSessionVO> listAllSessions(@Param("offset") int offset,
                                      @Param("limit") int limit);

    /**
     * 增量拉消息：只取 afterId 之后的新消息。
     * 轮询必须用它，否则每次都拉全量浪费带宽。
     */
    @Select("SELECT * FROM t_im_message WHERE session_id = #{sessionId} "
            + "AND id > #{afterId} ORDER BY id ASC LIMIT #{limit}")
    List<ImMessage> listMessagesAfter(@Param("sessionId") Long sessionId,
                                      @Param("afterId") Long afterId,
                                      @Param("limit") int limit);

    /**
     * 历史消息（分页，倒序取再反转让前端拿到正序）。
     */
    @Select("SELECT * FROM t_im_message WHERE session_id = #{sessionId} "
            + "ORDER BY id DESC LIMIT #{offset}, #{limit}")
    List<ImMessage> listHistory(@Param("sessionId") Long sessionId,
                                @Param("offset") int offset,
                                @Param("limit") int limit);

    /** 会话列表 VO：带上店铺名与买家昵称，前端直接展示不需二次请求 */
    class ImSessionVO {
        private Long id;
        private Long buyerId;
        private Long merchantId;
        private String shopName;
        private String buyerNickname;
        private Integer buyerUnread;
        private Integer merchantUnread;
        private String lastMessage;
        private java.time.LocalDateTime lastTime;

        public Long getId() { return id; }
        public void setId(Long id) { this.id = id; }
        public Long getBuyerId() { return buyerId; }
        public void setBuyerId(Long buyerId) { this.buyerId = buyerId; }
        public Long getMerchantId() { return merchantId; }
        public void setMerchantId(Long merchantId) { this.merchantId = merchantId; }
        public String getShopName() { return shopName; }
        public void setShopName(String shopName) { this.shopName = shopName; }
        public String getBuyerNickname() { return buyerNickname; }
        public void setBuyerNickname(String buyerNickname) { this.buyerNickname = buyerNickname; }
        public Integer getBuyerUnread() { return buyerUnread; }
        public void setBuyerUnread(Integer buyerUnread) { this.buyerUnread = buyerUnread; }
        public Integer getMerchantUnread() { return merchantUnread; }
        public void setMerchantUnread(Integer merchantUnread) { this.merchantUnread = merchantUnread; }
        public String getLastMessage() { return lastMessage; }
        public void setLastMessage(String lastMessage) { this.lastMessage = lastMessage; }
        public java.time.LocalDateTime getLastTime() { return lastTime; }
        public void setLastTime(java.time.LocalDateTime lastTime) { this.lastTime = lastTime; }
    }
}