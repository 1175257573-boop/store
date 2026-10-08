<template>
  <!-- 浮动入口按钮：始终在右下角，不遮挡浏览 -->
  <div class="chat-widget">
    <transition name="chat-fade">
      <div v-if="open" class="chat-panel">
        <!-- 头部 -->
        <div class="chat-header">
          <div class="chat-title">
            <span class="dot" />
            <div>
              <div class="title">智能客服</div>
              <div class="subtitle">
                {{ loading ? '正在输入…' : '在线 · 平均响应 1 秒内' }}
              </div>
            </div>
          </div>
          <div class="header-actions">
            <el-tooltip content="清空会话" placement="bottom">
              <button class="icon-btn" @click="resetSession">刷新</button>
            </el-tooltip>
            <el-tooltip content="收起" placement="bottom">
              <button class="icon-btn" @click="close">✕</button>
            </el-tooltip>
          </div>
        </div>

        <!-- 消息区 -->
        <div ref="scrollRef" class="chat-body">
          <div v-for="m in messages" :key="m.id" class="msg" :class="m.role">
            <div class="bubble">{{ m.content }}</div>
          </div>

          <!-- 打字机效果：等待服务端时先占位，避免界面"卡死" -->
          <div v-if="loading" class="msg bot">
            <div class="bubble typing">
              <span v-for="i in 3" :key="i" class="dot-inline" />
            </div>
          </div>
        </div>

        <!-- 快捷选项：把开场给的选项做成可点按钮，降低输入成本 -->
        <div v-if="!loading" class="quick-options">
          <button
            v-for="opt in quickOptions"
            :key="opt.value"
            class="opt-btn"
            @click="send(opt.value)"
          >{{ opt.label }}</button>
        </div>

        <!-- 输入区 -->
        <div class="chat-input">
          <el-input
            v-model="input"
            type="textarea"
            :rows="2"
            resize="none"
            maxlength="200"
            show-word-limit
            placeholder="输入您的问题，或点击上方选项"
            @keydown.enter.exact.prevent="send()"
          />
          <el-button
            type="danger"
            :loading="loading"
            :disabled="!input.trim()"
            @click="send()"
          >发送</el-button>
        </div>

        <div class="chat-tip">
          回答由知识库生成，参数以商品详情页为准；复杂问题建议咨询人工客服。
        </div>
      </div>
    </transition>

    <!-- 入口按钮 -->
    <button class="chat-trigger" @click="toggle">
      <el-icon :size="22"><ChatDotRound /></el-icon>
      <span v-if="!open" class="trigger-badge">客服</span>
    </button>
  </div>
</template>

<script setup>
import { nextTick, ref, watch, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatDotRound } from '@element-plus/icons-vue'
import { getChatGreeting, sendChatMessage } from '@/api/chat'

/**
 * 智能客服浮动聊天窗。
 *
 * 两个设计要点：
 * 1. 快捷选项直接用开场白给的编号 —— 「1」「2」「3」正则能 100% 命中，
 *    让用户不必自己组织表达（这是正则意图识别方案的关键配套）。
 * 2. 打字机占位：检索有耗时，没有占位用户会以为界面卡死。
 */

const props = defineProps({
  /** 当前浏览的商品，传入后客服会锁定该商品 */
  productName: { type: String, default: '' }
})

const emit = defineEmits(['close'])

const open = ref(false)
const loading = ref(false)
const input = ref('')
const messages = ref([])
const sessionId = ref('')
const scrollRef = ref(null)
let seq = 0

/** 与后端 ChatService 的 followupMenu 保持一致 */
const quickOptions = [
  { value: '1', label: '价格优惠' },
  { value: '2', label: '库存发货' },
  { value: '3', label: '参数规格' },
  { value: '4', label: '对比选购' },
  { value: '0', label: '转人工' }
]

function toggle() {
  open.value = !open.value
  if (open.value) {
    if (messages.value.length === 0) initSession()
    scrollToBottom()
  } else {
    emit('close')
  }
}

function close() {
  open.value = false
  emit('close')
}

async function initSession() {
  try {
    const res = await getChatGreeting('', props.productName)
    const data = res.data || {}
    sessionId.value = data.sessionId || ''
    messages.value = [{
      id: ++seq,
      role: 'bot',
      content: data.reply || '您好，请问想了解哪方面？'
    }]
    scrollToBottom()
  } catch (e) {
    messages.value = [{
      id: ++seq,
      role: 'bot',
      content: '客服初始化失败，请稍后重试。'
    }]
  }
}

/** 清空会话重开 —— 换商品浏览时需要，否则上下文还锁着上一款 */
function resetSession() {
  sessionId.value = ''
  messages.value = []
  initSession()
  ElMessage.success('会话已重置')
}

async function send(text) {
  const content = (text ?? input.value).trim()
  if (!content || loading.value) return

  messages.value.push({ id: ++seq, role: 'user', content })
  input.value = ''
  loading.value = true
  scrollToBottom()

  try {
    const res = await sendChatMessage({ sessionId: sessionId.value, message: content })
    const r = res.data || {}
    // 服务端返回的 sessionId 可能与本地不同（首次调用时本地为空）
    if (r.sessionId) sessionId.value = r.sessionId
    messages.value.push({
      id: ++seq,
      role: 'bot',
      content: r.reply || '抱歉，没有理解您的意思。'
    })
  } catch (e) {
    messages.value.push({
      id: ++seq,
      role: 'bot',
      content: '服务暂时不可用，请稍后重试。'
    })
  } finally {
    loading.value = false
    scrollToBottom()
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  })
}

// 切换商品时重置上下文：否则客服会拿上一款商品的知识回答新商品的问题
watch(() => props.productName, (nv, ov) => {
  if (nv !== ov && open.value && messages.value.length > 1) {
    resetSession()
  }
})

onUnmounted(() => { messages.value = [] })
</script>

<style scoped>
.chat-widget {
  position: fixed;
  right: 24px;
  bottom: 24px;
  z-index: 2000;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

/* ---------- 入口按钮 ---------- */
.chat-trigger {
  width: 52px;
  height: 52px;
  border-radius: 50%;
  background: var(--ec-primary);
  color: #fff;
  border: none;
  cursor: pointer;
  box-shadow: 0 4px 14px rgba(228, 57, 60, 0.4);
  display: flex;
  align-items: center;
  justify-content: center;
  position: relative;
  transition: transform 0.2s, box-shadow 0.2s;
}
.chat-trigger:hover {
  transform: scale(1.06);
  box-shadow: 0 6px 20px rgba(228, 57, 60, 0.5);
}
.trigger-badge {
  position: absolute;
  top: -4px;
  right: -4px;
  background: #fff;
  color: var(--ec-primary);
  font-size: 11px;
  padding: 1px 6px;
  border-radius: 9px;
  border: 1px solid var(--ec-primary);
  white-space: nowrap;
}

/* ---------- 面板 ---------- */
.chat-panel {
  position: absolute;
  right: 0;
  bottom: 64px;
  width: 380px;
  height: 520px;
  background: #fff;
  border-radius: 12px;
  box-shadow: 0 8px 40px rgba(0, 0, 0, 0.16);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

/* ---------- 头部 ---------- */
.chat-header {
  background: var(--ec-primary);
  color: #fff;
  padding: 12px 14px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  flex-shrink: 0;
}
.chat-title {
  display: flex;
  align-items: center;
  gap: 9px;
}
.chat-title .dot {
  width: 8px;
  height: 8px;
  background: #4ade80;
  border-radius: 50%;
  display: inline-block;
}
.chat-title .title {
  font-size: 15px;
  font-weight: 600;
}
.chat-title .subtitle {
  font-size: 11px;
  opacity: 0.85;
  margin-top: 1px;
}
.header-actions {
  display: flex;
  gap: 6px;
}
.icon-btn {
  background: rgba(255, 255, 255, 0.2);
  border: none;
  color: #fff;
  width: 26px;
  height: 26px;
  border-radius: 6px;
  cursor: pointer;
  font-size: 12px;
  line-height: 1;
}
.icon-btn:hover {
  background: rgba(255, 255, 255, 0.32);
}

/* ---------- 消息区 ---------- */
.chat-body {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #f7f8fa;
}
.msg {
  margin-bottom: 12px;
  display: flex;
}
.msg.user {
  justify-content: flex-end;
}
.bubble {
  max-width: 82%;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.bot .bubble {
  background: #fff;
  color: var(--ec-text);
  border: 1px solid var(--ec-border);
}
.msg.user .bubble {
  background: var(--ec-primary);
  color: #fff;
}
/* 打字机占位 */
.bubble.typing {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 12px 14px;
}
.dot-inline {
  width: 6px;
  height: 6px;
  background: #c0c4cc;
  border-radius: 50%;
  animation: blink 1.2s infinite;
}
.dot-inline:nth-child(2) { animation-delay: 0.2s; }
.dot-inline:nth-child(3) { animation-delay: 0.4s; }
@keyframes blink {
  0%, 60%, 100% { opacity: 0.3; }
  30% { opacity: 1; }
}

/* ---------- 快捷选项 ---------- */
.quick-options {
  padding: 8px 12px;
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  border-top: 1px solid var(--ec-border);
  background: #fff;
  flex-shrink: 0;
}
.opt-btn {
  border: 1px solid var(--ec-border);
  background: #fff;
  color: #606266;
  font-size: 12px;
  padding: 4px 10px;
  border-radius: 13px;
  cursor: pointer;
  transition: all 0.15s;
}
.opt-btn:hover {
  border-color: var(--ec-primary);
  color: var(--ec-primary);
  background: #fef5f5;
}

/* ---------- 输入区 ---------- */
.chat-input {
  padding: 10px 12px;
  display: flex;
  gap: 8px;
  align-items: flex-end;
  border-top: 1px solid var(--ec-border);
  background: #fff;
  flex-shrink: 0;
}
.chat-input :deep(.el-textarea__inner) {
  font-size: 13px;
  line-height: 1.6;
}
.chat-tip {
  padding: 0 12px 10px;
  font-size: 11px;
  color: var(--ec-text-light);
  background: #fff;
  flex-shrink: 0;
}

/* ---------- 动画 ---------- */
.chat-fade-enter-active,
.chat-fade-leave-active {
  transition: all 0.22s ease;
}
.chat-fade-enter-from,
.chat-fade-leave-to {
  opacity: 0;
  transform: translateY(14px) scale(0.97);
}

/* ---------- 窄屏适配 ---------- */
@media (max-width: 480px) {
  .chat-panel {
    width: calc(100vw - 24px);
    height: 70vh;
  }
  .chat-widget {
    right: 12px;
    bottom: 12px;
  }
}
</style>