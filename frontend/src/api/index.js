import request from '@/utils/request'

// ===== 用户 =====
export const login = data => request.post('/user/login', data)
export const register = data => request.post('/user/register', data)
export const getProfile = () => request.get('/user/profile')
export const updateProfile = data => request.put('/user/profile', data)
export const changePassword = params =>
  request.put('/user/password', null, { params })

// ===== 商品 =====
export const getProductList = params => request.get('/product/list', { params })
export const getProductDetail = id => request.get(`/product/${id}`)
export const getRelatedProducts = (id, limit = 6) =>
  request.get(`/product/${id}/related`, { params: { limit } })
export const getCategories = () => request.get('/product/categories')


// ===== 店铺 =====
export const getShopInfo = merchantId => request.get(`/product/shop/${merchantId}`)
// 店铺主页的商品列表：复用商品列表接口，传 merchantId 即可按店铺筛选
export const getShopProducts = params =>
  request.get('/product/list', { params: { sortBy: 'sales', ...params } })

// ===== 购物车 =====
export const getCart = () => request.get('/cart')
export const addToCart = data => request.post('/cart', data)
export const updateCartQuantity = (id, quantity) =>
  request.put(`/cart/${id}`, null, { params: { quantity } })
export const updateCartChecked = (id, checked) =>
  request.put(`/cart/${id}/checked`, null, { params: { checked } })
export const updateAllCartChecked = checked =>
  request.put('/cart/checked-all', null, { params: { checked } })
export const removeCartItem = id => request.delete(`/cart/${id}`)
export const removeCartBatch = ids => request.delete('/cart/batch', { data: ids })
export const getCartCount = () => request.get('/cart/count')

// ===== 地址 =====
export const getAddressList = () => request.get('/address')
export const getDefaultAddress = () => request.get('/address/default')
export const addAddress = data => request.post('/address', data)
export const updateAddress = (id, data) => request.put(`/address/${id}`, data)
export const deleteAddress = id => request.delete(`/address/${id}`)
export const setDefaultAddress = id => request.put(`/address/${id}/default`)

// ===== 订单 =====
export const createOrder = data => request.post('/order', data)
export const getOrderList = params => request.get('/order/list', { params })
export const getOrderDetail = id => request.get(`/order/${id}`)
export const cancelOrder = id => request.post(`/order/${id}/cancel`)
export const payOrder = id => request.post(`/order/${id}/pay`)
export const confirmOrder = id => request.post(`/order/${id}/confirm`)
export const getOrderCount = () => request.get('/order/count')

// ===== 秒杀活动管理（避免与其他模块重复定义，统一从这里导出）=====
export {
  publishActivity,
  updateActivity,
  listActivities,
  getActivityDetail,
  changeActivityStatus,
  deleteActivity,
  resetActivityStock,
  getActivityStockSummary,
  getCurrentActivity,
  seckill,
  querySeckillResult
} from './seckillAdmin'
