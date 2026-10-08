package com.ecommerce.service.chat;

/**
 * 会话状态。
 *
 * <p>会话状态存在 Redis 里（TTL 30 分钟），不用内存 ——
 * 多实例部署时内存状态会不一致，用户刷新页面或负载均衡切换就丢了上下文。
 *
 * <p>要存的两样东西：
 * <ul>
 *   <li>{@code lastIntent} —— 供极短输入（「嗯」「那个」）沿用上一轮意图</li>
 *   <li>{@code productName} —— <b>商品上下文锁定</b>。这是必需的：
 *       「曜石手机有货吗」→「续航怎么样」，第二句没有商品名，
 *       语义检索会混进同品类的其他商品。<b>客服答错对象比答不出来更糟。</b></li>
 * </ul>
 */
public class ChatSession {

    private String sessionId;

    /** 上一轮意图 */
    private ChatIntent lastIntent = ChatIntent.UNKNOWN;

    /** 上下文锁定的商品名 */
    private String productName;

    public ChatSession() {
    }

    public ChatSession(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public ChatIntent getLastIntent() {
        return lastIntent;
    }

    public void setLastIntent(ChatIntent lastIntent) {
        this.lastIntent = lastIntent == null ? ChatIntent.UNKNOWN : lastIntent;
    }

    public String getProductName() {
        return productName;
    }

    public void setProductName(String productName) {
        this.productName = productName;
    }
}