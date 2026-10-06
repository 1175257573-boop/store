import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * Axios 实例
 *
 * 统一处理三件事：
 * 1. 请求拦截：自动带上 JWT 令牌
 * 2. 响应拦截：把后端 { code, message, data } 结构拆包，业务层直接拿 data
 * 3. 错误兜底：401 跳登录、错误提示统一出口
 */
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// ---- 请求拦截：附加令牌 ----
request.interceptors.request.use(
  config => {
    const token = localStorage.getItem('token')
    if (token) {
      config.headers.Authorization = `Bearer ${token}`
    }
    return config
  },
  error => Promise.reject(error)
)

// ---- 响应拦截 ----
request.interceptors.response.use(
  response => {
    const res = response.data
    // 后端约定：HTTP 200 + code 200 表示成功
    if (res.code !== 200) {
      ElMessage.error(res.message || '操作失败')
      return Promise.reject(new Error(res.message || '操作失败'))
    }
    return res
  },
  error => {
    if (error.response) {
      const status = error.response.status
      const msg = error.response.data?.message || ''
      if (status === 401) {
        ElMessage.error('登录已失效，请重新登录')
        // 避免在登录页反复跳转
        if (!location.hash.includes('/login')) {
          localStorage.removeItem('token')
          localStorage.removeItem('user')
          location.hash = '#/login'
        }
      } else {
        ElMessage.error(msg || `请求失败（${status}）`)
      }
    } else if (error.code === 'ECONNABORTED') {
      ElMessage.error('请求超时，请检查网络')
    } else {
      ElMessage.error('网络异常，请确认后端服务已启动')
    }
    return Promise.reject(error)
  }
)

export default request
