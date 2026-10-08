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
          <router-link to="/seckill" class="nav-item">
            <el-icon><Lightning /></el-icon>秒杀
          </router-link>
          <!-- 入口路径必须按角色分流：管理员没有自己的店铺，
               /merchant/dashboard 是商家页，管理员进去会撞守卫被弹回来。
               与 router/index.js 里 /merchant 的默认重定向保持同一套规则。 -->
          <router-link v-if="userStore.isAdmin"
                       to="/merchant/audit" class="nav-item merchant-entry">
            <el-icon><Shop /></el-icon>管理后台
          </router-link>
          <router-link v-else-if="userStore.isMerchant"
                       to="/merchant/dashboard" class="nav-item merchant-entry">
            <el-icon><Shop /></el-icon>商家中心
          </router-link>
          <router-link to="/cart" class="nav-item">
            <el-badge :value="cartCount" :hidden="cartCount === 0" :max="99">
              <el-icon><ShoppingCart /></el-icon>
            </el-badge>
            购物车
          </router-link>
          <router-link to="/orders" class="nav-item">
            <el-icon><List /></el-icon>订单
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
  </div>
</template>

<script setup>
import { ref, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  Search, HomeFilled, ShoppingCart, List, User, ArrowDown, Lightning, Shop
} from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { getCartCount, getProductDetail } from '@/api'
import ChatWidget from '@/components/ChatWidget.vue'

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
  // 全局事件：加购/下单后由页面触发刷新角标，避免层层透传
  window.addEventListener('cart-change', refreshCartCount)
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
  gap: 4px;
  font-size: 14px;
  color: var(--ec-text);
  cursor: pointer;
  outline: none;
  white-space: nowrap;
}
.nav-item:hover {
  color: var(--ec-primary);
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
