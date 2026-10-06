import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import * as api from '@/api'

/**
 * 从 JWT 载荷里解出角色。
 *
 * <p><b>为什么需要它</b>：localStorage 里存的 user 对象可能来自旧版本，
 * 当时还没有 role 字段。此时 userInfo.role 为 undefined，
 * isAdmin / isMerchant 都是 false，管理员会被当成普通用户
 * 被引导到「申请商家入驻」页。</p>
 *
 * <p>JWT 的 payload 里带了 role（登录时签发），是服务端权威数据。
 * 路由守卫是同步执行的，跑在 fetchProfile 之前，所以只能从 token 里同步取。
 * 两者都取不到时返回 null，由守卫按「未知身份」处理。</p>
 */
function roleFromToken(token) {
  if (!token) return null
  const parts = token.split('.')
  if (parts.length !== 3) return null
  try {
    // JWT payload 是 base64url 编码的 JSON
    const payload = JSON.parse(atob(parts[1].replace(/-/g, '+').replace(/_/g, '/')))
    return typeof payload.role === 'number' ? payload.role : null
  } catch (e) {
    return null
  }
}

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem('token') || '')
  const userInfo = ref(JSON.parse(localStorage.getItem('user') || 'null'))

  /**
   * 有效角色：优先取 userInfo.role，缺失时从 JWT 载荷兜底。
   * <p>兜底是必要的：旧版 localStorage 数据 + 守卫同步执行，
   * 等 fetchProfile 返回就来不及了。</p>
   */
  const role = computed(() => {
    const r = userInfo.value?.role
    if (typeof r === 'number') return r
    return roleFromToken(token.value)
  })

  const isLogin = computed(() => !!token.value)
  const nickname = computed(() => userInfo.value?.nickname || '未登录')
  const isMerchant = computed(() => role.value === 1)
  const isAdmin = computed(() => role.value === 2)

  function setLogin(data) {
    token.value = data.token
    userInfo.value = {
      userId: data.userId,
      username: data.username,
      nickname: data.nickname,
      avatar: data.avatar,
      // 角色与店铺信息：前端据此展示商家入口、判断管理员菜单
      role: data.role,
      roleText: data.roleText,
      merchantId: data.merchantId,
      shopName: data.shopName
    }
    localStorage.setItem('token', data.token)
    localStorage.setItem('user', JSON.stringify(userInfo.value))
  }

  function setProfile(profile) {
    userInfo.value = { ...userInfo.value, ...profile }
    localStorage.setItem('user', JSON.stringify(userInfo.value))
  }

  function logout() {
    token.value = ''
    userInfo.value = null
    localStorage.removeItem('token')
    localStorage.removeItem('user')
  }

  /**
   * 拉取最新资料。
   * <p>除了同步昵称改动，还承担「修复旧版 localStorage 缺 role」的职责——
   * 拉回来后 role 就补齐了，之后不再依赖 JWT 兜底。</p>
   */
  async function fetchProfile() {
    if (!token.value) return
    try {
      const res = await api.getProfile()
      setProfile(res.data)
    } catch (e) {
      // 令牌失效已在拦截器处理，这里静默即可
    }
  }

  return {
    token, userInfo, role, isLogin, nickname, isMerchant, isAdmin,
    setLogin, setProfile, logout, fetchProfile
  }
})

// 供布局等非 store 组件复用：Pinia 尚未就绪时也能同步判断角色
export { roleFromToken }
