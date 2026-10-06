import request from '@/utils/request'

// ===== 入驻与店铺 =====
export const applyMerchant = data => request.post('/merchant/apply', data)
export const getMyApply = () => request.get('/merchant/apply/my')
export const revokeApply = id => request.post(`/merchant/apply/${id}/revoke`)
export const getMyShop = () => request.get('/merchant/shop')
export const updateMyShop = data => request.put('/merchant/shop', data)
export const getPublicShop = merchantId => request.get(`/product/shop/${merchantId}`)

// ===== 平台管理端 =====
export const listApplies = params => request.get('/merchant/admin/applies', { params })
export const auditApply = (id, pass, remark) =>
  request.post(`/merchant/admin/apply/${id}/audit`, null, { params: { pass, remark } })
export const listShops = params => request.get('/merchant/admin/shops', { params })
export const changeShopStatus = (id, status) =>
  request.post(`/merchant/admin/shop/${id}/status`, null, { params: { status } })
export const listPendingAudits = params => request.get('/merchant/admin/audits', { params })
export const getAdminTodo = () => request.get('/merchant/admin/todo')
export const auditProduct = (id, pass, reason) =>
  request.post(`/merchant/admin/product/${id}/audit`, null, { params: { pass, reason } })

// ===== 商品管理 =====
export const createMerchantProduct = data => request.post('/merchant/product', data)
export const updateMerchantProduct = (id, data) => request.put(`/merchant/product/${id}`, data)
export const deleteMerchantProduct = id => request.delete(`/merchant/product/${id}`)
export const changeProductStatus = (id, status) =>
  request.put(`/merchant/product/${id}/status`, null, { params: { status } })
export const listMyProducts = params => request.get('/merchant/product/list', { params })
export const getMerchantProductDetail = id => request.get(`/merchant/product/${id}`)
export const getProductSkus = id => request.get(`/merchant/product/${id}/skus`)
export const updateSku = (productId, skuId, params) =>
  request.put(`/merchant/product/${productId}/sku/${skuId}`, null, { params })
export const getProductCount = () => request.get('/merchant/product/count')

// ===== 订单发货 =====
export const listMerchantOrders = params => request.get('/merchant/order/list', { params })
export const getMerchantOrderDetail = id => request.get(`/merchant/order/${id}`)
export const shipOrder = (id, shipCompany, shipNo) =>
  request.post(`/merchant/order/${id}/ship`, null, { params: { shipCompany, shipNo } })
export const getMerchantOrderCount = () => request.get('/merchant/order/count')

// ===== 售后 =====
export const applyAfterSale = data => request.post('/merchant/after-sale', data)
export const getMyAfterSales = params => request.get('/merchant/after-sale/my', { params })
export const revokeAfterSale = id => request.post(`/merchant/after-sale/${id}/revoke`)
export const listMerchantAfterSales = params => request.get('/merchant/after-sale/list', { params })
export const getAfterSaleDetail = id => request.get(`/merchant/after-sale/${id}`)
export const approveAfterSale = (id, remark) =>
  request.post(`/merchant/after-sale/${id}/approve`, null, { params: { remark } })
export const rejectAfterSale = (id, remark) =>
  request.post(`/merchant/after-sale/${id}/reject`, null, { params: { remark } })
export const completeRefund = id => request.post(`/merchant/after-sale/${id}/complete`)
export const getAfterSaleCount = () => request.get('/merchant/after-sale/count')

// ===== 数据看板 =====
export const getDashboardOverview = () => request.get('/merchant/dashboard/overview')
export const getSalesTrend = days => request.get('/merchant/dashboard/trend', { params: { days } })
export const getTopProducts = limit => request.get('/merchant/dashboard/top', { params: { limit } })
export const getDashboardBadges = () => request.get('/merchant/dashboard/badges')
