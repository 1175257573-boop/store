<template>
  <div class="layout">
    <!-- 顶部导航 -->
    <header class="header">
      <div class="container header-inner">
        <router-link to="/" class="logo">
          <span class="logo-mark">优选</span>
          <span class="logo-text">商城</span>
        </router-link>

        <div class="search">
          <el-input
            v-model="keyword"
            placeholder="搜索商品，如：iPhone / 耳机 / 冰箱"
            clearable
            @keyup.enter="doSearch"
          >
            <template #prefix><el-icon><Search /></el-icon></template>
          </el-input>
          <el-button type="danger" @click="doSearch">搜索</el-button>
        </div>

        <nav class="nav">
          <router-link to="/" class="nav-item">
            <el-icon><HomeFilled /></el-icon>首页
          </router-link>
          <router-link to="/seckill" class="nav-item has-badge">
            <span class="icon-wrap"><el-icon><Lightning /></el-icon><NavBadge :count="seckillOngoing" /></span>秒杀
            <!-- 秒杀是「机会型」提醒：有进行中的活动就亮，结束自动消失 -->
            
          </router-link>
          <!-- 入口路径必须按角色分流：管理员没有自己的店铺，
               /merchant/dashboard 是商家页，管理员进去会撞守卫被弹回来。
               与 router/index.js 里 /merchant 的默认重定向保持同一套规则。 -->
          <router-link v-if="userStore.isAdmin"
                       to="/merchant/audit" class="nav-item merchant-entry has-badge">
            <el-icon><Shop /></el-icon>管理后台
            <!-- 管理员：待审核入驻 + 待审核商品 -->
            <NavBadge :count="merchantBadges.products + adminTodoTotal" />
          </router-link>
          <router-link v-else-if="userStore.isMerchant"
                       to="/merchant/dashboard" class="nav-item merchant-entry has-badge">
            <el-icon><Shop /></el-icon>商家中心
            <!-- 商家：待发货/待收货订单 + 待处理售后 + 待审核商品 -->
            <NavBadge :count="merchantBadges.orders + merchantBadges.afterSale" />
          </router-link>
          <!-- 购物车用「数量」语义而非「提醒」语义：
               角标是用户自己加的东西，始终有值。
               所以用 neutral 样式（灰底），与红色「有事要处理」区分开。 -->
          <router-link to="/cart" class="nav-item has-badge">
            <span class="icon-wrap"><el-icon><ShoppingCart /></el-icon><NavBadge :count="cartCount" variant="count" /></span>购物车
            
          </router-link>
          <router-link to="/orders" class="nav-item has-badge">
            <span class="icon-wrap"><el-icon><List /></el-icon><NavBadge :count="buyerBadges.orders" /></span>订单
            <!-- 买家待处理：待付款 + 已发货待收货 -->
            
          </router-link>
          <router-link v-if="userStore.isLogin" to="/messages" class="nav-item has-badge">
            <span class="icon-wrap"><el-icon><ChatDotRound /></el-icon><NavBadge :count="isMerchantView ? merchantBadges.messages : buyerBadges.messages" /></span>消息
            <!-- 买家侧是商家回复的未读；商家侧是买家咨询的未读 -->
            
          </router-link>

          <template v-if="userStore.isLogin">
            <el-dropdown @command="onCommand">
              <span class="nav-item user">
                <el-icon><User /></el-icon>{{ userStore.nickname }}
                <el-icon class="arrow"><ArrowDown /></el-icon>
              </span>
              <template #dropdown>
                <el-dropdown-menu>
                  <el-dropdown-item command="user">个人中心</el-dropdown-item>
                  <el-dropdown-item command="address">收货地址</el-dropdown-item>
                  <!-- 管理员与商家用不同 command，路径各自明确，
                       避免在处理函数里按角色二次判断 -->
                  <el-dropdown-item v-if="userStore.isAdmin"
                                    command="admin" divided>进入管理后台</el-dropdown-item>
                  <el-dropdown-item v-else-if="userStore.isMerchant"
                                    command="merchant" divided>进入商家中心</el-dropdown-item>
                  <el-dropdown-item v-else command="apply" divided>申请商家入驻</el-dropdown-item>
                  <el-dropdown-item command="logout" divided>退出登录</el-dropdown-item>
                </el-dropdown-menu>
              </template>
            </el-dropdown>
          </template>
          <template v-else>
            <router-link to="/login" class="nav-item login">登录 / 注册</router-link>
          </template>
        </nav>
      </div>
    </header>

    <!-- 主体 -->
    <main class="main">
      <router-view v-slot="{ Component }">
        <keep-alive include="ProductList">
          <component :is="Component" />
        </keep-alive>
      </router-view>
    </main>

    <!-- 页脚 -->
    <footer class="footer">
      <div class="container">
        <p>优选商城 · Vue3 + Spring Boot 3 + MyBatis-Plus + Redis + JWT</p>
        <p class="sub">本站为教学演示项目，商品与价格均为虚构信息</p>
      </div>
    </footer>

    <!-- 智能客服浮动窗口：全站可用。
         productName 从商品详情页透传，详情页会自动锁定该商品。 -->
    <ChatWidget :product-name="chatProductName" />

    <!-- 商家消息入口：买家显示悬浮按钮（带未读红点），商家走侧栏入口。 -->
    <MerchantChat v-if="userStore.isLogin" :trigger="!userStore.isMerchant" />
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted, watch, computed, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, HomeFilled, ShoppingCart, List, User, ArrowDown, Lightning, Shop,
  ChatDotRound
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import NavBadge from '@/components/NavBadge.vue'
import {
  buyerBadges, merchantBadges, refreshBadges, resetBadges,
  seckillOngoing
} from '@/composables/useBadge'
import { getAdminTodo } from '@/api/merchant'
import { getCartCount, getProductDetail } from '@/api'
import ChatWidget from '@/components/ChatWidget.vue'
import MerchantChat from '@/components/MerchantChat.vue'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const keyword = ref('')
const cartCount = ref(0)

/**
 * 当前浏览的商品名，传给客服做上下文锁定。
 *
 * 在详情页时带上商品名，用户问「续航怎么样」客服就知道是哪一款；
 * 离开详情页清空，避免拿上一页的商品回答当前页的问题。
 */
const chatProductName = ref('')

/**
 * 商家视角判定：商家与管理员的「消息」红点含义不同。
 * 买家看到的是商家回复的未读；商家看到的是买家咨询的未读。
 */
const isMerchantView = computed(() =>
  userStore.isMerchant || userStore.isAdmin)

/**
 * 是否窄屏（导航项只显示图标）。
 *
 * <p>窄屏下角标要改挂到图标正上方 —— 横向贴右上角会盖住图标。
 * 监听 resize 而不是只在 mounted 判一次，旋转屏幕/拖窗口也能响应。
 */
const isNarrow = ref(window.innerWidth <= 900)

function updateNarrow() {
  isNarrow.value = window.innerWidth <= 900
}

/**
 * 管理员待办总数（待审核入驻 + 待审核商品）。
 * <p>单独拉而不是混进 useBadge —— 它只在管理后台有意义，
 * 放进通用 composable 会让买家侧也去请求一个用不到的接口。</p>
 */
const adminTodoTotal = ref(0)

async function loadAdminTodo() {
  if (!userStore.isAdmin) {
    adminTodoTotal.value = 0
    return
  }
  try {
    const res = await getAdminTodo()
    const d = res?.data || {}
    adminTodoTotal.value = (d.applyPending || 0) + (d.productPending || 0)
  } catch (e) {
    adminTodoTotal.value = 0
  }
}

async function syncChatProduct() {
  const id = route.params.id
  if (route.name === 'product-detail' && id) {
    try {
      const res = await getProductDetail(id)
      chatProductName.value = res.data?.name || ''
    } catch (e) {
      chatProductName.value = ''
    }
  } else {
    chatProductName.value = ''
  }
}

watch(() => route.fullPath, syncChatProduct)

/** 拉取购物车角标 */
async function refreshCartCount() {
  if (!userStore.isLogin) {
    cartCount.value = 0
    return
  }
  try {
    const res = await getCartCount()
    cartCount.value = res.data || 0
  } catch (e) {
    // 角标失败不打扰用户
  }
}

function doSearch() {
  router.push({ path: '/', query: keyword.value ? { keyword: keyword.value } : {} })
}

async function onCommand(cmd) {
  if (cmd === 'logout') {
    try {
      await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
    } catch {
      return // 用户取消
    }
    userStore.logout()
    ElMessage.success('已退出登录')
    router.push('/login')
  } else if (cmd === 'user') {
    router.push('/user')
  } else if (cmd === 'address') {
    router.push('/address')
  } else if (cmd === 'admin') {
    router.push('/merchant/audit')
  } else if (cmd === 'merchant') {
    router.push('/merchant/dashboard')
  } else if (cmd === 'apply') {
    router.push('/merchant/apply')
  }
}

onMounted(() => {
  refreshCartCount()
  // 首次进入也要同步一次：直接访问详情页 URL 时 watch 不会触发
  syncChatProduct()
  refreshBadges()
  if (userStore.isAdmin) loadAdminTodo()
  // 全局事件：加购/下单后由页面触发刷新角标，避免层层透传
  window.addEventListener('cart-change', refreshCartCount)
  // 发消息/下单/发货等动作都会改变红点，统一在这里刷新
  window.addEventListener('badge-change', refreshBadges)
  window.addEventListener('resize', updateNarrow)
  startBadgePolling()
})

onUnmounted(() => {
  window.removeEventListener('cart-change', refreshCartCount)
  window.removeEventListener('badge-change', refreshBadges)
  window.removeEventListener('resize', updateNarrow)
  stopBadgePolling()
})

/**
 * 红点轮询：30 秒一次。
 *
 * <p>不用 WebSocket 是因为量小（演示/教学场景），
 * 30 秒足以让「对方刚发的消息」及时出现在角标上，又不压后端。
 *
 * <p>页面隐藏时暂停 —— 用户不在看页面时轮询没有意义，
 * 反而会无谓消耗服务器（你那台只有 2 核）。
 */
let badgeTimer = null

function startBadgePolling() {
  stopBadgePolling()
  badgeTimer = setInterval(() => {
    if (document.hidden || !userStore.isLogin) return
    refreshBadges()
    if (userStore.isAdmin) loadAdminTodo()
  }, 30000)
}

function stopBadgePolling() {
  if (badgeTimer) {
    clearInterval(badgeTimer)
    badgeTimer = null
  }
}

// 从后台切回前台时立刻刷一次（浏览器会把定时器节流，间隔可能很长）
document.addEventListener('visibilitychange', () => {
  if (!document.hidden && userStore.isLogin) {
    refreshBadges()
    if (userStore.isAdmin) loadAdminTodo()
  }
})

/**
 * 「进入页面即清除」的时机。
 *
 * <p>红点由服务端数据决定，前端**不做本地清零** ——
 * 本地清零刷新就回来，用户会以为没清掉。
 * 这里做的是「进入相关页面后重新拉一次」：
 * 拉消息接口会顺带 markRead，订单列表页展示后服务端计数自然变化，
 * 回到导航栏时数字就少了。
 */
watch(() => route.path, async (path) => {
  if (!userStore.isLogin) return
  await nextTick()
  refreshBadges()
  if (userStore.isAdmin) loadAdminTodo()
})

// 切换账号：先清零再刷新，否则 A 账号的红点会留给 B 账号看
watch(() => userStore.token, () => {
  resetBadges()
  if (userStore.isLogin) refreshBadges()
})
</script>

<style scoped>
.layout {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
}

.header {
  background: #fff;
  box-shadow: 0 1px 6px rgba(0, 0, 0, 0.06);
  position: sticky;
  top: 0;
  z-index: 100;
}
.header-inner {
  display: flex;
  align-items: center;
  gap: 24px;
  height: 64px;
}

.logo {
  display: flex;
  align-items: baseline;
  gap: 4px;
  font-size: 20px;
  font-weight: 700;
  flex-shrink: 0;
}
.logo-mark {
  color: #fff;
  background: var(--ec-primary);
  padding: 2px 8px;
  border-radius: 6px;
}
.logo-text {
  color: var(--ec-text);
}

.search {
  flex: 1;
  display: flex;
  gap: 8px;
  max-width: 520px;
}
.search :deep(.el-input) {
  flex: 1;
}

.nav {
  display: flex;
  align-items: center;
  gap: 20px;
  margin-left: auto;
}
.nav-item {
  display: flex;
  align-items: center;
  /* 10px 而非 4px：角标要向右突出 6px 骑在图标右上角，
     gap 至少要比这个大，否则角标会伸进文字区。
     实测 gap=4px 时角标右缘 969 > 文字左缘 964，压了 5px。 */
  gap: 10px;
  font-size: 14px;
  color: var(--ec-text);
  cursor: pointer;
  outline: none;
  white-space: nowrap;
}
.nav-item:hover {
  color: var(--ec-primary);
}

/* 带角标的导航项需要定位上下文，否则角标会冒到 header 上 */
.nav-item.has-badge {
  position: relative;
}

/*
 * 图标容器 —— 角标的定位基准。
 *
 * <p><b>为什么角标要挂图标而不是整个导航项</b>：导航项是
 * 「图标 + gap(4px) + 文字」的 flex 布局，角标若相对整项定位在右上角，
 * 会横向伸进文字区压住文案（实测压住了「消息」的「息」字）。
 * 挂在图标上则天然隔着 gap，<b>结构上不可能压到文字</b>。
 */
.icon-wrap {
  position: relative;
  display: inline-flex;
  align-items: center;
  justify-content: center;
}

/* 窄屏隐藏文字只留图标时，红点往右挪一点，免得压到图标本体 */
@media (max-width: 900px) {
  /* 窄屏优先保证红点可见：导航文字会挤出屏幕，
     但红点是「有事要处理」的信号，不能跟着一起消失。
     做法是把文字藏起来只留图标，导航项本身横向滚动。 */
  .header-inner {
    flex-wrap: wrap;
    gap: 10px;
  }
  .nav {
    /* 图标 + 间距的宽度：只留图标才放得下 */
    order: 3;
    width: 100%;
    margin-left: 0;      /* 换行后不该再靠右对齐 */
    overflow-x: auto;
    justify-content: flex-start;
    padding-bottom: 4px;
    -webkit-overflow-scrolling: touch;
  }
  .nav::-webkit-scrollbar {
    height: 0;          /* 横向滚动条不占视觉空间 */
  }
  .nav-item {
    flex-shrink: 0;      /* 不让图标被压扁 */
    font-size: 13px;
  }
  /* 窄屏只剩图标：角标往图标右上角收，避免超出图标边界 */
  .nav-item.has-badge .nav-badge,
  .nav-item.has-badge :deep(.nav-badge) {
    top: -7px;
    right: -9px;
  }
}
.nav-item.router-link-active {
  color: var(--ec-primary);
}
.nav-item.login {
  color: var(--ec-primary);
  font-weight: 600;
}
.nav-item.merchant-entry {
  color: var(--ec-primary);
  background: #fdf0f0;
  padding: 4px 10px;
  border-radius: 20px;
}
.arrow {
  font-size: 12px;
}

.main {
  flex: 1;
  padding-bottom: 40px;
}

.footer {
  background: #fff;
  border-top: 1px solid var(--ec-border);
  padding: 24px 0;
  text-align: center;
  color: var(--ec-text-light);
  font-size: 13px;
}
.footer p {
  margin: 4px 0;
}
.footer .sub {
  font-size: 12px;
}
</style>
