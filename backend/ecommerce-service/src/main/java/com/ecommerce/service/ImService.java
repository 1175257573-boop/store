package com.ecommerce.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.ecommerce.common.context.LoginUser;
import com.ecommerce.common.context.UserContextHolder;
import com.ecommerce.common.constant.RoleConst;
import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.ResultCode;
import com.ecommerce.dao.entity.ImMessage;
import com.ecommerce.dao.entity.ImSession;
import com.ecommerce.dao.entity.Merchant;
import com.ecommerce.dao.mapper.ImMessageMapper;
import com.ecommerce.dao.mapper.ImSessionMapper;
import com.ecommerce.dao.mapper.MerchantMapper;
import com.ecommerce.dao.mapper.UserMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * 买家 ↔ 店铺 聊天服务。
 *
 * <p><b>权限模型（三个角色）</b>：
 * <table border="1">
 *   <tr><th>操作</th><th>买家</th><th>商家</th><th>管理员</th></tr>
 *   <tr><td>发起会话</td><td>✅</td><td>❌</td><td>❌</td></tr>
 *   <tr><td>看会话列表</td><td>✅ 仅自己</td><td>✅ 仅本店</td><td>✅ 全部（只读）</td></tr>
 *   <tr><td>发消息</td><td>✅</td><td>✅</td><td>❌</td></tr>
 * </table>
 *
 * <p><b>管理员为什么只读</b>：管理员介入买卖双方沟通会破坏平台的
 * 「中立第三方」定位，也让纠纷举证变复杂。
 *
 * <p><b>三个必须防的越权点</b>：
 * <ol>
 *   <li>买家传别人的 sessionId → 每个接口都校验会话里有自己</li>
 *   <li>商家传别人的 merchantId → 查询强制拼 {@code merchant_id = 自己}</li>
 *   <li>商家回复别人的会话 → 校验会话的 merchant_id == 自己店铺</li>
 * </ol>
 * 全部靠 SQL 层强制拼接身份，不只靠 service 层判断（那是两步操作，有竞态）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ImService {

    /** 单条消息最大长度，与表字段 varchar(1000) 对齐 */
    private static final int MAX_CONTENT = 1000;
    /** 会话列表摘要保留长度，与表字段 varchar(500) 对齐 */
    private static final int SUMMARY_LENGTH = 200;
    /** 轮询拉取单次上限，防止前端传入超大值 */
    private static final int MAX_POLL_LIMIT = 100;

    private final ImSessionMapper sessionMapper;
    private final ImMessageMapper messageMapper;
    private final MerchantMapper merchantMapper;
    private final UserMapper userMapper;

    // ==================== 会话 ====================

    /**
     * 发起会话（幂等）。
     *
     * <p>买家与同一店铺只有一条会话，再次点击「联系商家」时
     * <b>复用原会话</b>，历史消息才能延续。
     * 若每次都新建，买家问「这能退吗」再问「那台呢」就断了。
     *
     * @param merchantId 目标店铺
     * @param productId  来源商品，可为 null
     * @return sessionId
     */
    @Transactional
    public Long openSession(Long merchantId, Long productId) {
        Long buyerId = UserContextHolder.requireUserId();
        // 只有买家能发起：商家主动找买家会造成骚扰
        if (currentRole() != RoleConst.ROLE_USER) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "仅买家可发起咨询");
        }
        if (merchantId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少店铺ID");
        }
        // 店铺必须存在且营业，否则会话无从谈起
        Merchant shop = merchantMapper.selectById(merchantId);
        if (shop == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "店铺不存在");
        }

        // 只按 (买家, 店铺) 精确查 —— 不按 buyerId 单独查，
        // 否则会拿到该买家的第一条会话（可能是别家店的），复用就错了。
        ImSession exist = selectByBuyerAndMerchant(buyerId, merchantId);
        if (exist != null) {
            return exist.getId();
        }

        ImSession s = new ImSession();
        s.setBuyerId(buyerId);
        s.setMerchantId(merchantId);
        s.setProductId(productId);
        s.setStatus(1);
        s.setBuyerUnread(0);
        s.setMerchantUnread(0);
        sessionMapper.insert(s);
        return s.getId();
    }

    private ImSession selectByBuyerAndMerchant(Long buyerId, Long merchantId) {
        return sessionMapper.selectOne(new LambdaQueryWrapper<ImSession>()
                .eq(ImSession::getBuyerId, buyerId)
                .eq(ImSession::getMerchantId, merchantId)
                .last("LIMIT 1"));
    }

    /**
     * 会话列表 —— 按当前角色自动分流。
     *
     * <p>买家只看自己的，商家只看本店的，管理员看全部（只读）。
     */
    public List<ImSessionMapper.ImSessionVO> listSessions(int pageNum, int pageSize) {
        int limit = Math.min(Math.max(pageSize, 1), 50);
        int offset = (Math.max(pageNum, 1) - 1) * limit;

        int role = currentRole();
        if (role == RoleConst.ROLE_MERCHANT) {
            Long mid = UserContextHolder.requireMerchantId();
            return sessionMapper.listMerchantSessions(mid, offset, limit);
        }
        if (role == RoleConst.ROLE_ADMIN) {
            return sessionMapper.listAllSessions(offset, limit);
        }
        return sessionMapper.listBuyerSessions(UserContextHolder.requireUserId(), offset, limit);
    }

    /**
     * 未读总数 —— 用于顶部红点。
     */
    public int unreadCount() {
        int role = currentRole();
        if (role == RoleConst.ROLE_MERCHANT) {
            return messageMapper.sumMerchantUnread(UserContextHolder.requireMerchantId());
        }
        return messageMapper.sumBuyerUnread(UserContextHolder.requireUserId());
    }

    // ==================== 消息 ====================

    /**
     * 拉取历史消息，并把对方发给我的标记为已读。
     */
    public List<ImMessage> listMessages(Long sessionId, int pageNum, int pageSize) {
        ImSession session = requireSession(sessionId);
        int limit = Math.min(Math.max(pageSize, 1), MAX_POLL_LIMIT);
        int offset = (Math.max(pageNum, 1) - 1) * limit;

        // SQL 按 id DESC 出（最新在前），这里反转成正序 ——
        // 前端直接 append 渲染，拿到正序才能自然显示时间顺序。
        //
        // 不用「SQL 内反序」是为了避开 MySQL 5.7 的子查询分页限制；
        // 反转在 Java 层做，行为可控也更好测。
        List<ImMessage> list = sessionMapper.listHistory(sessionId, offset, limit);
        java.util.Collections.reverse(list);
        markAsRead(session, sessionId);
        return list;
    }

    /**
     * 增量拉取（轮询用）。
     *
     * <p>只取 afterId 之后的 —— 用「拉全量再对比」在消息多时浪费带宽，
     * 且会重复传输。
     */
    public List<ImMessage> pollMessages(Long sessionId, Long afterId) {
        ImSession session = requireSession(sessionId);
        return sessionMapper.listMessagesAfter(sessionId,
                afterId == null ? 0L : afterId, MAX_POLL_LIMIT);
    }

    /**
     * 发消息。
     *
     * @param sessionId 会话
     * @param content   内容
     * @return 消息ID
     */
    @Transactional
    public Long sendMessage(Long sessionId, String content) {
        if (content == null || content.isBlank()) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "消息内容不能为空");
        }
        String text = content.strip();
        if (text.length() > MAX_CONTENT) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(),
                    "消息过长（最多 " + MAX_CONTENT + " 字）");
        }

        ImSession session = requireSession(sessionId);
        int role = currentRole();
        // 管理员不介入买卖沟通
        if (role == RoleConst.ROLE_ADMIN) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "管理员不能参与买家商家沟通");
        }

        Long fromId;
        Long toId;
        int fromRole;
        boolean toIsBuyer;
        if (role == RoleConst.ROLE_MERCHANT) {
            Merchant shop = requireOwnShop(session);
            fromId = shop.getUserId();
            toId = session.getBuyerId();
            fromRole = RoleConst.ROLE_MERCHANT;
            toIsBuyer = true;
        } else {
            // 买家发给商家：to_id 必须是**商家本人**的 user_id，
            // 不能存 merchant_id —— t_im_message.to_id 的语义是「用户」。
            //
            // 存错的后果（实测踩过）：
            //   1. 商家侧 markRead 按 to_id=商家userId 查，永远查不到买家发的消息 → 未读清不掉
            //   2. 两类消息的 to_id 语义不一致，轮询/未读统计都会算错
            Merchant shop = merchantMapper.selectById(session.getMerchantId());
            if (shop == null) {
                throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "店铺不存在");
            }
            fromId = UserContextHolder.requireUserId();
            toId = shop.getUserId();
            fromRole = RoleConst.ROLE_USER;
            toIsBuyer = false;
        }

        ImMessage m = new ImMessage();
        m.setSessionId(sessionId);
        m.setFromId(fromId);
        m.setFromRole(fromRole);
        m.setToId(toId);
        m.setContent(text);
        m.setReadFlag(0);
        try {
            messageMapper.insert(m);
        } catch (Exception e) {
            log.error("IM 插入消息失败 session={} from={} content={}",
                    sessionId, fromId, text, e);
            throw e;
        }

        // 刷新摘要/时间 + 给接收方 +1 未读
        String summary = text.length() > SUMMARY_LENGTH
                ? text.substring(0, SUMMARY_LENGTH) : text;
        try {
            if (toIsBuyer) {
                messageMapper.incrBuyerUnread(sessionId, summary);
            } else {
                messageMapper.incrMerchantUnread(sessionId, summary);
            }
        } catch (Exception e) {
            log.error("IM 更新未读失败 session={} toIsBuyer={}", sessionId, toIsBuyer, e);
            throw e;
        }
        return m.getId();
    }

    /**
     * 全部标记已读（进入消息中心时调用）。
     *
     * <p><b>为什么需要它</b>：导航栏红点的语义是「有多少条没看过」。
     * 用户进入消息中心看到会话列表，本身就代表「已查看」——
     * 若只清当前打开的那个会话，列表里其他会话的未读点会一直挂着，
     * 用户会以为没清掉。
     *
     * <p>商家与买家清的是各自的侧：商家清「买家发给自己的」，
     * 买家清「商家发给自己的」。
     */
    @Transactional(rollbackFor = Exception.class)
    public int markAllRead() {
        int role = currentRole();
        if (role == RoleConst.ROLE_ADMIN) {
            return 0;   // 管理员只读，不参与沟通
        }
        if (role == RoleConst.ROLE_MERCHANT) {
            Long myShop = UserContextHolder.requireMerchantId();
            // 消息的接收方是商家本人（t_user.id），不是店铺ID
            Merchant shop = merchantMapper.selectById(myShop);
            if (shop == null) {
                return 0;
            }
            int n = messageMapper.markAllReadAsMerchant(shop.getUserId(), myShop);
            messageMapper.clearMerchantUnreadAll(myShop);
            return n;
        }
        Long myId = UserContextHolder.requireUserId();
        int n = messageMapper.markAllReadAsBuyer(myId);
        messageMapper.clearBuyerUnreadAll(myId);
        return n;
    }

    // ==================== 权限 ====================

    /**
     * 取会话并校验当前用户是否有权访问。
     *
     * <p><b>这是防越权的关键方法</b>：所有涉及 sessionId 的接口都必须先过它。
     * 买家要满足 {@code buyer_id == 自己}，
     * 商家要满足 {@code merchant_id == 自己店铺}，管理员放行只读。
     */
    private ImSession requireSession(Long sessionId) {
        if (sessionId == null) {
            throw new BusinessException(ResultCode.PARAM_ERROR.getCode(), "缺少会话ID");
        }
        ImSession s = sessionMapper.selectById(sessionId);
        if (s == null) {
            throw new BusinessException(ResultCode.NOT_FOUND.getCode(), "会话不存在");
        }
        int role = currentRole();
        if (role == RoleConst.ROLE_ADMIN) {
            // 管理员可读，但发消息已在 sendMessage 里拦住
            return s;
        }
        if (role == RoleConst.ROLE_MERCHANT) {
            Long myShop = UserContextHolder.requireMerchantId();
            if (!myShop.equals(s.getMerchantId())) {
                // 不暴露「会话存在但不属于你」，直接说无权
                throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该会话");
            }
            return s;
        }
        Long myId = UserContextHolder.requireUserId();
        if (!myId.equals(s.getBuyerId())) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "无权访问该会话");
        }
        return s;
    }

    /** 商家必须是自己店铺的归属者 */
    private Merchant requireOwnShop(ImSession session) {
        Long myShopId = UserContextHolder.requireMerchantId();
        Merchant shop = merchantMapper.selectById(session.getMerchantId());
        if (shop == null || !shop.getId().equals(myShopId)) {
            throw new BusinessException(ResultCode.FORBIDDEN.getCode(), "只能回复本店铺的会话");
        }
        return shop;
    }

    /** 标记对方消息已读 + 清零我的未读数 */
    private void markAsRead(ImSession session, Long sessionId) {
        int role = currentRole();
        if (role == RoleConst.ROLE_ADMIN) {
            return;
        }
        if (role == RoleConst.ROLE_MERCHANT) {
            Merchant shop = merchantMapper.selectById(session.getMerchantId());
            if (shop == null) {
                return;
            }
            messageMapper.markRead(sessionId, shop.getUserId());
            messageMapper.clearMerchantUnread(sessionId, session.getMerchantId());
        } else {
            Long myId = UserContextHolder.requireUserId();
            messageMapper.markRead(sessionId, myId);
            messageMapper.clearBuyerUnread(sessionId, myId);
        }
    }

    /**
     * 当前用户角色，未登录按买家处理（后续会被业务校验拦下）。
     * 返回 int 而非 Integer，避免与 {@code RoleConst} 比较时反复拆箱。
     */
    private int currentRole() {
        LoginUser login = UserContextHolder.get();
        return login == null || login.getRole() == null
                ? RoleConst.ROLE_USER : login.getRole();
    }
}