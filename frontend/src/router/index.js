import { createRouter, createWebHashHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '@/stores/user'

/**
 * 路由配置
 *
 * 使用 hash 模式：无需服务端配合做 history fallback，
 * 直接用 nginx 托管静态文件也能正常工作。
 *
 * meta.requiresAuth 为 true 的路由会进入路由守卫，未登录跳登录页。
 */
const routes = [
  {
    path: '/',
    component: () => import('@/layout/MainLayout.vue'),
    children: [
      {
        path: '',
        name: 'home',
        component: () => import('@/views/HomeView.vue'),
        meta: { title: '首页' }
      },
      {
        path: 'product/:id',
        name: 'product-detail',
        component: () => import('@/views/ProductDetailView.vue'),
        meta: { title: '商品详情' }
      },
      {
        path: 'cart',
        name: 'cart',
        component: () => import('@/views/CartView.vue'),
        meta: { title: '购物车', requiresAuth: true }
      },
      {
        path: 'checkout',
        name: 'checkout',
        component: () => import('@/views/CheckoutView.vue'),
        meta: { title: '确认订单', requiresAuth: true }
      },
      {
        path: 'orders',
        name: 'orders',
        component: () => import('@/views/OrderListView.vue'),
        meta: { title: '我的订单', requiresAuth: true }
      },
      {
        path: 'seckill',
        name: 'seckill',
        component: () => import('@/views/SeckillView.vue'),
        meta: { title: '限时秒杀' }
      },
      {
        path: 'order/:id',
        name: 'order-detail',
        component: () => import('@/views/OrderDetailView.vue'),
        meta: { title: '订单详情', requiresAuth: true }
      },
      {
        path: 'address',
        name: 'address',
        component: () => import('@/views/AddressView.vue'),
        meta: { title: '收货地址', requiresAuth: true }
      },
      {
        path: 'user',
        name: 'user',
        component: () => import('@/views/UserCenterView.vue'),
        meta: { title: '个人中心', requiresAuth: true }
      }
    ]
  },
  {
    path: '/login',
    name: 'login',
    component: () => import('@/views/LoginView.vue'),
    meta: { title: '登录' }
  },
  {
    path: '/register',
    name: 'register',
    component: () => import('@/views/RegisterView.vue'),
    meta: { title: '注册' }
  },
  {
    path: '/:pathMatch(.*)*',
    name: 'not-found',
    component: () => import('@/views/NotFoundView.vue'),
    meta: { title: '页面不存在' }
  }
]

// 商家端：独立布局 + 独立守卫（只需商家或管理员身份）
const merchantRoutes = [
  {
    path: '/merchant/apply',
    name: 'merchant-apply',
    component: () => import('@/views/merchant/ApplyView.vue'),
    meta: { title: '商家入驻', requiresAuth: true }
  },
  {
    path: '/merchant',
    component: () => import('@/layout/MerchantLayout.vue'),
    meta: { requiresAuth: true, requiresMerchant: true },
    children: [
      // 默认落地页按角色分流：管理员没有自己的店，进「经营概览」只会拿到空数据，
      // 应当直接进待办最多的「入驻审核」。这里用函数式重定向读 localStorage 里的角色。
      {
        path: '',
        // 用 store 的 isAdmin 判断（内部含 JWT 兜底）。
        // 自己读 localStorage 会绕过兜底：旧版缓存里没有 role 字段时，
        // 管理员会被判成普通用户而落到「申请入驻」页。
        redirect: () => (useUserStore().isAdmin ? '/merchant/audit' : '/merchant/dashboard')
      },
      { path: 'dashboard', name: 'merchant-dashboard',
        component: () => import('@/views/merchant/DashboardView.vue'),
        meta: { title: '经营概览', merchantOnly: true } },
      { path: 'product', name: 'merchant-product',
        component: () => import('@/views/merchant/ProductManageView.vue'),
        meta: { title: '商品管理', merchantOnly: true } },
      { path: 'order', name: 'merchant-order',
        component: () => import('@/views/merchant/OrderManageView.vue'),
        meta: { title: '订单管理', merchantOnly: true } },
      { path: 'after-sale', name: 'merchant-after-sale',
        component: () => import('@/views/merchant/AfterSaleView.vue'),
        meta: { title: '售后处理', merchantOnly: true } },
      { path: 'shop', name: 'merchant-shop',
        component: () => import('@/views/merchant/ShopSettingView.vue'),
        meta: { title: '店铺设置', merchantOnly: true } },
      // 以下两个仅管理员可见
      { path: 'audit', name: 'merchant-audit',
        component: () => import('@/views/merchant/ApplyAuditView.vue'),
        meta: { title: '入驻审核', requiresAdmin: true } },
      { path: 'product-audit', name: 'merchant-product-audit',
        component: () => import('@/views/merchant/ProductAuditView.vue'),
        meta: { title: '商品审核', requiresAdmin: true } }
    ]
  }
]

const router = createRouter({
  history: createWebHashHistory(),
  routes: [...routes, ...merchantRoutes],
  scrollBehavior: () => ({ top: 0 })
})

router.beforeEach((to, from, next) => {
  const token = localStorage.getItem('token')
  if (to.meta.requiresAuth && !token) {
    // 记录来源，登录后跳回
    next({ path: '/login', query: { redirect: to.fullPath } })
    return
  }
  // 已登录时访问登录/注册页，直接回首页
  if ((to.path === '/login' || to.path === '/register') && token) {
    next('/')
    return
  }

  // 商家端权限：需要商家或管理员身份。
  // 这里只做粗判（页面级），细粒度数据隔离在后端 SQL 里做——
  // 前端守卫挡的是误入，后端挡的才是越权。
  if (to.meta.requiresMerchant || to.meta.requiresAdmin || to.meta.merchantOnly) {
    // 用 store 里的 role（内部含 JWT 兜底），不要自己读 localStorage：
    // 旧版缓存的 user 对象没有 role 字段，直接读会把管理员误判成普通用户
    const { isMerchant, isAdmin } = useUserStore()

    // 管理员专属页
    if (to.meta.requiresAdmin && !isAdmin) {
      ElMessage.warning('该功能仅平台管理员可用')
      next(isMerchant ? '/merchant/dashboard' : '/')
      return
    }
    // 商家专属页：管理员没有自己的店，进这些页只会拿到空数据。
    // 这是兜底 —— 正常入口已在 MainLayout 里按角色分流，这里防的是手动敲 URL。
    if (to.meta.merchantOnly && isAdmin) {
      ElMessage.info('管理后台没有经营数据，请从「入驻审核 / 商品审核」进入')
      next('/merchant/audit')
      return
    }
    // 需要商家身份但既不是商家也不是管理员 -> 引导去入驻页
    if (to.meta.requiresMerchant && !isMerchant && !isAdmin) {
      next('/merchant/apply')
      return
    }
  }
  next()
})

router.afterEach(to => {
  document.title = to.meta.title ? `${to.meta.title} - 优选商城` : '优选商城'
})

export default router
