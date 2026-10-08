<template>
  <div class="merchant-chat">
    <!-- 内嵌模式：直接在页面里渲染，不用抽屉 -->
    <div v-if="embedded" class="chat-wrap embedded-wrap">
      <div class="chat-head">
        <div class="head-info">
          <div class="head-title">{{ headTitle }}</div>
          <div class="head-sub">{{ headSub }}</div>
        </div>
      </div>
      <div v-show="showList" class="session-list">
        <div v-if="sessions.length === 0" class="empty">
          <el-icon :size="34"><ChatDotRound /></el-icon>
          <p>还没有咨询记录</p>
          <span class="empty-sub">从商品页点「联系商家」开始</span>
        </div>
        <div v-for="s in sessions" :key="s.id" class="session-item" @click="enterSession(s)">
          <div class="session-avatar">{{ avatarText(s.shopName || s.buyerNickname) }}</div>
          <div class="session-main">
            <div class="session-top">
              <span class="session-name">{{ isMerchant ? s.buyerNickname : s.shopName }}</span>
              <span class="session-time">{{ timeText(s.lastTime) }}</span>
            </div>
            <div class="session-bottom">
              <span class="session-last">{{ s.lastMessage || '暂无消息' }}</span>
              <el-badge v-if="unreadOf(s) > 0" :value="unreadOf(s)" :max="99" class="session-badge" />
            </div>
          </div>
        </div>
      </div>
      <div v-show="!showList" class="msg-panel">
        <div ref="scrollRef" class="msg-list">
          <div v-if="messages.length === 0" class="empty">
            <el-icon :size="34"><ChatLineRound /></el-icon>
            <p>发送第一条消息咨询商家</p>
          </div>
          <div v-for="m in messages" :key="m.id" class="msg" :class="m.mine ? 'mine' : 'theirs'">
            <div class="bubble">{{ m.content }}</div>
            <div class="msg-time">{{ timeText(m.createTime, true) }}</div>
          </div>
        </div>
        <div class="input-bar">
          <el-input v-model="draft" type="textarea" :rows="2" resize="none"
            maxlength="1000" show-word-limit placeholder="输入消息，Enter 发送"
            @keydown.enter.exact.prevent="send()" />
          <el-button type="primary" :loading="sending" :disabled="!draft.trim()" @click="send()">发送</el-button>
        </div>
      </div>
    </div>

    <!-- 抽屉模式：会话列表 + 消息区 -->
    <el-drawer
      v-else
      v-model="visible"
      :direction="rtl ? 'rtl' : 'ltr'"
      :size="drawerSize"
      :with-header="false"
      :modal="false"
      custom-class="chat-drawer"
    >
      <div class="chat-wrap">
        <!-- ============ 头部 ============ -->
        <div class="chat-head">
          <button class="back-btn" @click="showList ? switchToMessage() : close()">
            <el-icon><ArrowLeft v-if="showList" /><Close v-else /></el-icon>
          </button>
          <div class="head-info">
            <div class="head-title">{{ headTitle }}</div>
            <div class="head-sub">{{ headSub }}</div>
          </div>
        </div>

        <!-- ============ 会话列表 ============ -->
        <div v-show="showList" class="session-list">
          <div v-if="sessions.length === 0" class="empty">
            <el-icon :size="34"><ChatDotRound /></el-icon>
            <p>还没有咨询记录</p>
            <span class="empty-sub">从商品页点「联系商家」开始</span>
          </div>
          <div
            v-for="s in sessions"
            :key="s.id"
            class="session-item"
            @click="enterSession(s)"
          >
            <div class="session-avatar">{{ avatarText(s.shopName || s.buyerNickname) }}</div>
            <div class="session-main">
              <div class="session-top">
                <span class="session-name">
                  {{ isMerchant ? s.buyerNickname : s.shopName }}
                </span>
                <span class="session-time">{{ timeText(s.lastTime) }}</span>
              </div>
              <div class="session-bottom">
                <span class="session-last">{{ s.lastMessage || '暂无消息' }}</span>
                <el-badge
                  v-if="unreadOf(s) > 0"
                  :value="unreadOf(s)"
                  :max="99"
                  class="session-badge"
                />
              </div>
            </div>
          </div>
        </div>

        <!-- ============ 消息区 ============ -->
        <div v-show="!showList" class="msg-panel">
          <div ref="scrollRef" class="msg-list">
            <div v-if="messages.length === 0" class="empty">
              <el-icon :size="34"><ChatLineRound /></el-icon>
              <p>发送第一条消息咨询商家</p>
            </div>
            <div
              v-for="m in messages"
              :key="m.id"
              class="msg"
              :class="m.mine ? 'mine' : 'theirs'"
            >
              <div class="bubble">{{ m.content }}</div>
              <div class="msg-time">{{ timeText(m.createTime, true) }}</div>
            </div>
          </div>

          <div class="input-bar">
            <el-input
              v-model="draft"
              type="textarea"
              :rows="2"
              resize="none"
              maxlength="1000"
              show-word-limit
              placeholder="输入消息，Enter 发送"
              @keydown.enter.exact.prevent="send()"
            />
            <el-button
              type="primary"
              :loading="sending"
              :disabled="!draft.trim()"
              @click="send()"
            >发送</el-button>
          </div>
        </div>
      </div>
    </el-drawer>

    <!-- 触发按钮 -->
    <button v-if="trigger" class="chat-trigger" :class="{ active: visible }" @click="toggle">
      <el-badge :value="totalUnread" :hidden="totalUnread === 0" :max="99">
        <el-icon :size="20"><ChatDotRound /></el-icon>
      </el-badge>
    </button>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, ChatDotRound, ChatLineRound, Close
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import {
  listImMessages, listImSessions, getImUnread, openImSession,
  pollImMessages, sendImMessage
} from '@/api/im'

/**
 * 商家聊天面板。
 *
 * 两个使用形态：
 * 1. 抽屉（列表 + 对话）—— 挂到 MainLayout，全站可用
 * 2. 触发按钮 —— 从商品详情页点「联系商家」直接打开
 *
 * 实时性用轮询（3 秒），接口已预留 SSE 升级空间：
 * 只要 appendMessages 与拉取方式解耦，换长连接不用改这里。
 */

const props = defineProps({
  /** 右侧抽屉（买家视角）。商家端用 rtl=false 从左侧出 */
  rtl: { type: Boolean, default: true },
  /** 是否显示悬浮触发按钮 */
  trigger: { type: Boolean, default: false },
  /** 传入商品 ID 时打开面板会直接发起会话 */
  merchantId: { type: [String, Number], default: null },
  /**
   * 内嵌模式：不走抽屉，直接把面板渲染在页面里。
   *
   * 不用 CSS 把 el-drawer 改成静态 —— overlay 的挂载点在 body 下，
   * scoped 样式够不到，强行覆盖还会踩到 Element Plus 内部结构。
   * 两套模板更稳。
   */
  embedded: { type: Boolean, default: false },
  productId: { type: [String, Number], default: null }
})

const router = useRouter()
const userStore = useUserStore()

const visible = ref(false)
const showList = ref(true)
const sessions = ref([])
const messages = ref([])
const draft = ref('')
const sending = ref(false)
const unread = ref(0)
const scrollRef = ref(null)

let currentSessionId = ref(null)
/** 轮询游标：只拉 afterId 之后的新消息 */
let afterId = 0
let timer = null

const isMerchant = computed(() => userStore.isMerchant)
const drawerSize = computed(() =>
  window.innerWidth < 480 ? '100%' : '400px'
)

const headTitle = computed(() => {
  if (showList.value) return isMerchant.value ? '买家咨询' : '我的咨询'
  const s = currentSession()
  if (!s) return '会话'
  return isMerchant.value ? (s.buyerNickname || '买家') : (s.shopName || '商家')
})

const headSub = computed(() => {
  const s = currentSession()
  if (showList.value) return `${sessions.value.length} 个会话`
  if (!s) return ''
  return isMerchant.value ? '买家咨询时间' : '通常几分钟内回复'
})

const totalUnread = computed(() =>
  isMerchant.value
    ? sessions.value.reduce((a, s) => a + (s.merchantUnread || 0), 0)
    : sessions.value.reduce((a, s) => a + (s.buyerUnread || 0), 0)
)

function currentSession() {
  return sessions.value.find((s) => s.id === currentSessionId.value)
}

/** 会话列表里当前用户的未读数 */
function unreadOf(s) {
  return isMerchant.value ? (s.merchantUnread || 0) : (s.buyerUnread || 0)
}

function avatarText(name) {
  if (!name) return '店'
  return name.replace(/^(旗舰|专营).*/, '').slice(0, 1)
}

function timeText(t, withTime = false) {
  if (!t) return ''
  const d = new Date(String(t).replace('T', ' ').replace(' ', 'T'))
  if (Number.isNaN(d.getTime())) return ''
  const now = new Date()
  const sameDay = d.toDateString() === now.toDateString()
  const hh = String(d.getHours()).padStart(2, '0')
  const mm = String(d.getMinutes()).padStart(2, '0')
  if (sameDay) return `${hh}:${mm}`
  return withTime
    ? `${d.getMonth() + 1}/${d.getDate()} ${hh}:${mm}`
    : `${d.getMonth() + 1}/${d.getDate()}`
}

function toggle() {
  if (visible.value) close()
  else open()
}

async function open() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push({ path: '/login', query: { redirect: '/messages' } })
    return
  }
  visible.value = true
  showList.value = true
  await loadSessions()
  loadUnread()
  startPolling()
}

function close() {
  visible.value = false
  stopPolling()
}

function switchToMessage() {
  showList.value = false
  messages.value = []
  afterId = 0
  scrollToBottom()
}

async function loadSessions() {
  try {
    const res = await listImSessions()
    sessions.value = res.data || []
  } catch (e) {
    sessions.value = []
  }
}

async function loadUnread() {
  try {
    const res = await getImUnread()
    unread.value = res.data?.unread || 0
  } catch (e) {
    unread.value = 0
  }
}

/** 进入某个会话 */
async function enterSession(s) {
  switchToMessage()
  currentSessionId.value = s.id
  try {
    const res = await listImMessages(s.id)
    messages.value = (res.data || []).map((m) => ({
      ...m,
      mine: isMerchant.value ? m.fromRole === 1 : m.fromRole === 0
    }))
    afterId = messages.value.length
      ? messages.value[messages.value.length - 1].id
      : 0
    scrollToBottom()
  } catch (e) {
    messages.value = []
  }
}

/**
 * 从商品详情页发起：拿到 sessionId 并直接进入对话。
 * 幂等 —— 后端保证同一买家与店铺只有一条会话。
 */
async function openWithShop(merchantId, productId) {
  if (!userStore.isLogin) {
    ElMessage.warning('登录后可咨询商家')
    router.push({ path: '/login', query: { redirect: router.currentRoute.value.fullPath } })
    return
  }
  if (!merchantId) return
  try {
    const res = await openImSession(merchantId, productId)
    const sid = res.data?.sessionId
    if (!sid) return
    await loadSessions()
    const target = sessions.value.find((s) => s.id === sid)
    if (target) {
      await enterSession(target)
    } else {
      // 会话刚建、列表还没刷新出来时直接进
      switchToMessage()
      currentSessionId.value = sid
      messages.value = []
      afterId = 0
    }
    visible.value = true
    startPolling()
  } catch (e) {
    ElMessage.error('发起咨询失败，请稍后再试')
  }
}

async function send() {
  const content = draft.value.trim()
  if (!content || sending.value || !currentSessionId.value) return
  sending.value = true
  draft.value = ''
  try {
    const res = await sendImMessage(currentSessionId.value, content)
    messages.value.push({
      id: res.data?.messageId || Date.now(),
      content,
      mine: true,
      fromRole: isMerchant.value ? 1 : 0,
      createTime: new Date().toISOString()
    })
    afterId = messages.value[messages.value.length - 1].id
    scrollToBottom()
  } catch (e) {
    draft.value = content   // 失败要把内容还回去，不然用户白输了
  } finally {
    sending.value = false
  }
}

/** 轮询：只在对话页拉消息，其他时候只刷未读数 */
function startPolling() {
  stopPolling()
  timer = setInterval(async () => {
    if (!visible.value) return
    if (showList.value || !currentSessionId.value) {
      // 不在对话页：轻量刷未读
      await loadSessions()
      await loadUnread()
      return
    }
    try {
      const res = await pollImMessages(currentSessionId.value, afterId)
      const list = res.data || []
      if (!list.length) return
      messages.value.push(...list.map((m) => ({
        ...m,
        mine: isMerchant.value ? m.fromRole === 1 : m.fromRole === 0
      })))
      afterId = list[list.length - 1].id
      scrollToBottom()
    } catch (e) {
      // 网络抖动忽略，下轮继续
    }
  }, 3000)
}

function stopPolling() {
  if (timer) {
    clearInterval(timer)
    timer = null
  }
}

function scrollToBottom() {
  nextTick(() => {
    if (scrollRef.value) {
      scrollRef.value.scrollTop = scrollRef.value.scrollHeight
    }
  })
}

watch(() => props.merchantId, (nv) => {
  if (nv && !visible.value) openWithShop(nv, props.productId)
})

onMounted(async () => {
  if (props.embedded) {
    // 内嵌模式：直接进列表，不弹抽屉
    if (userStore.isLogin) {
      visible.value = true
      showList.value = true
      await loadSessions()
      startPolling()
    }
  }
  if (userStore.isLogin) {
    await loadUnread()
    // 悬浮按钮才做后台轮询；抽屉模式用户主动开
    if (props.trigger) {
      timer = setInterval(async () => {
        if (!visible.value) loadUnread()
      }, 10000)
    }
  }
})

onUnmounted(stopPolling)

defineExpose({ open, openWithShop })
</script>

<style scoped>
.merchant-chat {
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'PingFang SC',
    'Hiragino Sans GB', 'Microsoft YaHei', sans-serif;
}

.chat-wrap {
  height: 100%;
  display: flex;
  flex-direction: column;
  background: #fff;
}
.embedded-wrap {
  height: calc(100vh - 240px);
  min-height: 420px;
}

/* ---------- 头部 ---------- */
.chat-head {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 14px;
  border-bottom: 1px solid var(--ec-border, #e4e7ed);
  flex-shrink: 0;
}
.back-btn {
  width: 30px;
  height: 30px;
  border: none;
  background: #f5f6f7;
  border-radius: 6px;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #606266;
}
.back-btn:hover {
  background: #ebedf0;
}
.head-title {
  font-size: 15px;
  font-weight: 600;
  color: var(--ec-text, #303133);
}
.head-sub {
  font-size: 11px;
  color: var(--ec-text-light, #909399);
  margin-top: 2px;
}

/* ---------- 会话列表 ---------- */
.session-list {
  flex: 1;
  overflow-y: auto;
}
.session-item {
  display: flex;
  gap: 10px;
  padding: 12px 14px;
  cursor: pointer;
  border-bottom: 1px solid #f5f6f7;
  transition: background 0.15s;
}
.session-item:hover {
  background: #fafafa;
}
.session-avatar {
  width: 38px;
  height: 38px;
  border-radius: 8px;
  background: var(--ec-primary, #e4393c);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 15px;
  font-weight: 600;
  flex-shrink: 0;
}
.session-main {
  flex: 1;
  min-width: 0;
}
.session-top,
.session-bottom {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
}
.session-name {
  font-size: 14px;
  font-weight: 500;
  color: var(--ec-text, #303133);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-time {
  font-size: 11px;
  color: var(--ec-text-light, #909399);
  flex-shrink: 0;
}
.session-last {
  font-size: 12px;
  color: var(--ec-text-light, #909399);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.session-badge {
  flex-shrink: 0;
}

/* ---------- 消息区 ---------- */
.msg-panel {
  flex: 1;
  display: flex;
  flex-direction: column;
}
.msg-list {
  flex: 1;
  overflow-y: auto;
  padding: 14px;
  background: #f7f8fa;
}
.msg {
  margin-bottom: 14px;
  display: flex;
  flex-direction: column;
}
.msg.mine {
  align-items: flex-end;
}
.bubble {
  max-width: 78%;
  padding: 9px 12px;
  border-radius: 10px;
  font-size: 13px;
  line-height: 1.7;
  white-space: pre-wrap;
  word-break: break-word;
}
.msg.theirs .bubble {
  background: #fff;
  border: 1px solid var(--ec-border, #e4e7ed);
  color: var(--ec-text, #303133);
}
.msg.mine .bubble {
  background: var(--ec-primary, #e4393c);
  color: #fff;
}
.msg-time {
  font-size: 10px;
  color: var(--ec-text-light, #909399);
  margin-top: 3px;
  padding: 0 4px;
}

/* ---------- 输入 ---------- */
.input-bar {
  padding: 10px 12px;
  border-top: 1px solid var(--ec-border, #e4e7ed);
  display: flex;
  gap: 8px;
  align-items: flex-end;
  flex-shrink: 0;
}

/* ---------- 空态 ---------- */
.empty {
  padding: 50px 20px;
  text-align: center;
  color: var(--ec-text-light, #909399);
}
.empty p {
  margin: 10px 0 4px;
  font-size: 13px;
}
.empty-sub {
  font-size: 11px;
}

/* ---------- 悬浮触发 ---------- */
.chat-trigger {
  position: fixed;
  right: 90px;
  bottom: 24px;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background: #fff;
  border: 1px solid var(--ec-border, #e4e7ed);
  color: var(--ec-primary, #e4393c);
  cursor: pointer;
  box-shadow: 0 3px 12px rgba(0, 0, 0, 0.12);
  display: flex;
  align-items: center;
  justify-content: center;
  z-index: 1999;
  transition: transform 0.2s;
}
.chat-trigger:hover {
  transform: scale(1.06);
}
.chat-trigger.active {
  background: var(--ec-primary, #e4393c);
  color: #fff;
}
</style>

<style>
/* 非 scoped：el-drawer 的自定义类在组件外部 */
.chat-drawer .el-drawer__body {
  padding: 0;
  overflow: hidden;
}
</style>