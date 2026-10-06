import request from '@/utils/request'

// ===== 活动管理（商家 / 管理员）=====
export const publishActivity = data => request.post('/seckill/admin/publish', data)
export const updateActivity = (id, data) => request.put(`/seckill/admin/${id}`, data)
export const listActivities = params => request.get('/seckill/admin/list', { params })
export const getActivityDetail = id => request.get(`/seckill/admin/detail/${id}`)
export const changeActivityStatus = (id, status) =>
  request.post(`/seckill/admin/${id}/status`, null, { params: { status } })
export const deleteActivity = id => request.delete(`/seckill/admin/${id}`)
export const resetActivityStock = id => request.post(`/seckill/admin/${id}/reset-stock`)
export const getActivityStockSummary = id => request.get(`/seckill/admin/${id}/stock-summary`)

// ===== 秒杀接口（用户侧）=====
export const seckill = data => request.post('/seckill', data)
export const querySeckillResult = requestId =>
  request.get('/seckill/result', { params: { requestId } })
export const getCurrentActivity = () => request.get('/seckill/activity/current')
