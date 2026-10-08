import request from '@/utils/request'

/**
 * 买家 ↔ 店铺 聊天接口。
 *
 * 会话模型：`买家 × 店铺` 一对一，不是 `买家 × 商品`。
 * 买家与同一店铺只有一条会话，重复点「联系商家」会复用原会话，历史消息不断。
 */

/**
 * 发起会话。
 *
 * 幂等：同一买家与店铺只有一条会话，重复调用返回同一个 sessionId。
 * @param {number} merchantId 店铺ID
 * @param {number} [productId]  来源商品，可选
 */
export const openImSession = (merchantId, productId) =>
  request.post('/im/session', { merchantId, productId })

/**
 * 会话列表。
 *
 * 后端按登录身份自动分流：买家看自己的，商家看本店的，管理员看全部。
 * 传入 pageNum/pageSize 只是分页，不是筛选条件。
 */
export const listImSessions = (params = {}) =>
  request.get('/im/sessions', { params: { pageNum: 1, pageSize: 20, ...params } })

/** 未读消息总数（用于顶部红点） */
export const getImUnread = () =>
  request.get('/im/unread')

/** 历史消息（同时把对方发给我的标记已读） */
export const listImMessages = (sessionId, params = {}) =>
  request.get('/im/message', { params: { sessionId, pageNum: 1, pageSize: 20, ...params } })

/**
 * 增量拉取消息（轮询用）。
 *
 * 只返回 afterId 之后的新消息 —— 不能"拉全量再对比"，
 * 消息多了会浪费带宽且重复传输。
 */
export const pollImMessages = (sessionId, afterId) =>
  request.get('/im/message/poll', { params: { sessionId, afterId } })

/** 发送消息 */
export const sendImMessage = (sessionId, content) =>
  request.post('/im/message', { sessionId, content })
/**
 * 全部标记已读。
 *
 * <p>进入消息中心时调用 —— 看到会话列表就算「已查看」。
 * 只清当前打开的会话不够：列表里其他会话的未读点会一直挂着。
 */
export const markAllImRead = () =>
  request.post('/im/read-all')
