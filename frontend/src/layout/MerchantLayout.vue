<template>
  <div class="merchant-layout">
    <!-- 侧边导航 -->
    <aside class="sidebar">
      <div class="shop-card">
        <div class="shop-logo">{{ shopName.charAt(0) }}</div>
        <div class="shop-info">
          <div class="shop-name">{{ shopName }}</div>
          <div class="shop-status" :class="statusClass">{{ statusText }}</div>
        </div>
      </div>

      <el-menu
        :default-active="$route.path"
        router
        class="menu"
        background-color="transparent"
      >
        <!-- 商家自己的经营页面：只对有店铺的商家显示。
             管理员没有自己的店，进这些页会拿到空数据或 6001 报错。 -->
        <template v-if="!isAdmin">
          <el-menu-item index="/merchant/dashboard">
            <el-icon><DataLine /></el-icon>经营概览
          </el-menu-item>
          <el-menu-item index="/merchant/product">
            <el-icon><Goods /></el-icon>商品管理
            <el-badge v-if="badges.productPending" :value="badges.productPending" class="menu-badge" />
          </el-menu-item>
          <el-menu-item index="/merchant/order">
            <el-icon><List /></el-icon>订单管理
            <el-badge v-if="badges.pendingShip" :value="badges.pendingShip" class="menu-badge" />
          </el-menu-item>
          <el-menu-item index="/merchant/after-sale">
            <el-icon><RefreshLeft /></el-icon>售后处理
            <el-badge v-if="badges.pendingAfterSale" :value="badges.pendingAfterSale" class="menu-badge" />
          </el-menu-item>
          <el-menu-item index="/merchant/shop">
            <el-icon><Shop /></el-icon>店铺设置
          </el-menu-item>
        </template>

        <!-- 平台管理员专属：只做审核与管控。
             路径必须与 router/index.js 的定义严格对应：
               audit         -> 入驻审核（ApplyAuditView）
               product-audit -> 商品审核（ProductAuditView）
             /merchant/apply 是「用户提交入驻申请」页，管理员不该出现在这里。 -->
        <el-menu-item v-if="isAdmin" index="/merchant/audit">
          <el-icon><Stamp /></el-icon>入驻审核
          <el-badge v-if="adminTodo.applyPending > 0"
                    :value="adminTodo.applyPending" class="menu-badge" />
        </el-menu-item>
        <el-menu-item v-if="isAdmin" index="/merchant/product-audit">
          <el-icon><DocumentChecked /></el-icon>商品审核
          <el-badge v-if="adminTodo.productPending > 0"
                    :value="adminTodo.productPending" class="menu-badge" />
        </el-menu-item>
      </el-menu>

      <!-- 管理员待办汇总：点进任意审核页都能看到还剩多少待处理 -->
      <div v-if="isAdmin && adminTodo.total > 0" class="todo-hint">
        <el-icon><Bell /></el-icon>
        <span>有 <b>{{ adminTodo.total }}</b> 条待审核</span>
      </div>

      <div class="back-to-shop">
        <el-button link @click="$router.push('/')">
          <el-icon><ArrowLeft /></el-icon>返回商城首页
        </el-button>
      </div>
    </aside>

    <!-- 内容区 -->
    <main class="content">
      <router-view :key="$route.fullPath" />
    </main>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  DataLine, Goods, List, RefreshLeft, Shop, Stamp,
  DocumentChecked, ArrowLeft, Bell
} from '@element-plus/icons-vue'
import { getMyShop, getDashboardBadges, getAdminTodo } from '@/api/merchant'
import { roleFromToken } from '@/stores/user'

const route = useRoute()

/**
 * 管理员身份同步判断，不依赖 store。
 *
 * <p>store 的 isAdmin 在 Pinia 尚未与 app 绑定时可能是默认值 false
 * （守卫早于 mount 执行就会踩到），导致管理员看到的是商家菜单。</p>
 *
 * <p>这里直接从 localStorage / JWT 读，与 router 守卫同一套逻辑，
 * 保证「守卫放行」与「菜单显示」判断一致。</p>
 */
function readIsAdmin() {
  try {
    const raw = localStorage.getItem('user')
    if (raw && typeof JSON.parse(raw)?.role === 'number') {
      return JSON.parse(raw).role === 2
    }
  } catch (e) { /* 落到 JWT */ }
  return roleFromToken(localStorage.getItem('token')) === 2
}

const shopName = ref('商家中心')
const statusText = ref('正常')
const status = ref(1)

/** 商家侧角标：待发货、待售后、待审核商品 */
const badges = ref({})
/** 管理员侧角标：待审入驻、待审商品 */
const adminTodo = ref({ applyPending: 0, productPending: 0, total: 0 })

/**
 * 必须用 store 的 isAdmin，不能自己读 userInfo.role。
 * store 内部对 role 缺失做了 JWT 载荷兜底（应对旧版 localStorage 缓存）；
 * 这里直接读 userInfo?.role 会绕过兜底，管理员被误判成普通用户，
 * 表现是「菜单里没有审核项，且被守卫重定向到申请入驻页」。
 */
const isAdmin = ref(readIsAdmin())
const statusClass = computed(() => (status.value === 1 ? '' : 'frozen'))

async function loadShop() {
  if (isAdmin.value) {
    // 管理员没有自己的店铺，标题显示平台身份
    shopName.value = '平台管理后台'
    statusText.value = '管理员'
    return
  }
  try {
    const res = await getMyShop()
    if (res.data) {
      shopName.value = res.data.shopName
      status.value = res.data.status
      statusText.value = { 1: '正常营业', 2: '已冻结', 3: '已注销' }[res.data.status] || '未知'
    }
  } catch (e) {
    // 非商家访问时接口返回 6001，这里不打扰，让子路由各自提示
  }
}

async function loadBadges() {
  if (isAdmin.value) {
    // 管理员的待办数来自审核队列，与商家的经营角标是两套数据
    try {
      const res = await getAdminTodo()
      if (res.data) {
        adminTodo.value = {
          applyPending: res.data.applyPending || 0,
          productPending: res.data.productPending || 0,
          total: res.data.total || 0
        }
      }
    } catch (e) { /* 忽略 */ }
    return
  }
  try {
    const res = await getDashboardBadges()
    if (res.data) {
      badges.value = {
        pendingShip: res.data.pendingShip || 0,
        pendingAfterSale: res.data.pendingAfterSale || 0,
        productPending: res.data.productPending || 0
      }
    }
  } catch (e) { /* 忽略 */ }
}

onMounted(() => {
  // 挂载后再同步一次身份，确保菜单按正确角色渲染
  isAdmin.value = readIsAdmin()
  loadShop()
  loadBadges()
  // 审核页处理完会派发 todo-changed，这里重新拉待办数让红点实时减少
  window.addEventListener('todo-changed', refreshTodo)
})

onUnmounted(() => {
  window.removeEventListener('todo-changed', refreshTodo)
})

function refreshTodo() {
  loadBadges()
  if (isAdmin.value) loadShop()
}
</script>

<style scoped>
.merchant-layout {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  max-width: 1380px;
  margin: 0 auto;
  padding: 16px;
  min-height: calc(100vh - 64px);
}
@media (max-width: 900px) {
  .merchant-layout {
    grid-template-columns: 1fr;
  }
}

.sidebar {
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 16px 8px;
  display: flex;
  flex-direction: column;
  height: fit-content;
  position: sticky;
  top: 80px;
}

.shop-card {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 8px 12px 16px;
  border-bottom: 1px solid var(--ec-border);
  margin-bottom: 8px;
}
.shop-logo {
  width: 40px;
  height: 40px;
  border-radius: 8px;
  background: var(--ec-primary);
  color: #fff;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 18px;
  font-weight: 500;
  flex-shrink: 0;
}
.shop-name {
  font-size: 14px;
  font-weight: 500;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.shop-status {
  font-size: 12px;
  color: #67c23a;
}
.shop-status.frozen {
  color: var(--ec-primary);
}

.menu {
  border-right: none;
}
.menu :deep(.el-menu-item) {
  border-radius: 6px;
  margin-bottom: 2px;
}
.menu :deep(.el-menu-item.is-active) {
  background: #fdf0f0;
  color: var(--ec-primary);
}
.menu-badge {
  margin-left: auto;
}

.todo-hint {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-top: 12px;
  padding: 8px 10px;
  background: #fef0f0;
  color: var(--ec-primary);
  border-radius: 6px;
  font-size: 12px;
}
.todo-hint b {
  font-size: 14px;
  font-weight: 600;
}

.back-to-shop {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid var(--ec-border);
  text-align: center;
}

.content {
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 20px;
  min-width: 0;
}
</style>
