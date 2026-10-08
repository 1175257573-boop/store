import request from '@/utils/request'

/**
 * 智能客服接口。
 *
 * 会话流程：
 *   1. 打开窗口调 getChatGreeting，拿到 sessionId 与开场白
 *   2. 之后每次发消息都带上 sessionId —— 服务端靠它维持上下文
 *     （商品锁定、上一轮意图），缺了会导致连续追问答非所问
 */

// 打开窗口时取开场白。带上当前商品名可让客服锁定该商品。
export const getChatGreeting = (sessionId, productName) =>
  request.get('/chat/greeting', { params: { sessionId, productName } })

// 发消息。sessionId 必须传，否则服务端拿不到上下文。
export const sendChatMessage = data =>
  request.post('/chat/send', data)