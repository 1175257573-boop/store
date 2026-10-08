import { computed, ref } from 'vue'
import { getCartCount, getOrderCount } from '@/api'
import { getImUnread } from '@/api/im'
import {
  getAfterSaleCount,
  getMerchantOrderCount,
  getProductCount
} from '@/api/merchant'
import { getCurrentActivity } from '@/api/seckill'
import { useUserStore } from '@/stores/user'

/**
 * 导航栏角标的统一状态源。
 *
 * <p><b>为什么集中管理</b>：角标数量来自 6 个不同接口，
 * 分散在各处维护会出现「消息页刷新后购物车角标被清零」这类互相污染。
 * 集中到这里后，每个角标只由自己的数据源决定。
 *
 * <p><b>「未读」与「待处理」不是一回事</b>：
 * <ul>
 *   <li>未读：对方发了消息我还没看</li>
 *   <li>待处理：业务上等着我做事</li>
 * </ul>
 * 把「已完成」也算进去，角标会永远是脏的 ——
 * 用户点进去发现没事，几次之后就不信这个角标了。
 */

/** 超出该值显示为「99+」，与主流电商一致 */
export const BADGE_MAX = 99

/** 小于该值用纯红点，大于用数字角标（两位数宽度约等于圆点两倍，纯点不够醒目） */
export const DOT_THRESHOLD = 10

// ==================== 原始状态 ====================

const imUnread = ref(0)
const buyerOrderPending = ref(0)
const cartCount = ref(0)
/** 秒杀进行中的活动数（0/1）。机会型提醒：活动结束自动消失。 */
export const seckillOngoing = ref(0)
const merchantOrderPending = ref(0)
const merchantAfterSalePending = ref(0)
const merchantProductPending = ref(0)
const merchantImUnread = ref(0)

// ==================== 状态常量 ====================

/** 订单状态（对齐后端 BizConst），红点只算「等着我动作」的 */
const ORDER = {
  UNPAID: 0,   // 待付款 → 买家要付款
  PAID: 1,     // 已付款 → 买家等发货 / 商家待发货
  SHIPPED: 2,  // 已发货 → 买家待收货
  FINISHED: 3,
  CANCELLED: 4
}

/** 秒杀活动状态（后端 BizConst.ACTIVITY_*） */
const ACTIVITY = {
  NOT_START: 0,
  RUNNING: 1,
  FINISHED: 2,
  CANCELLED: 3
}

// ==================== 刷新 ====================

/**
 * 秒杀提醒：进行中的活动数。
 *
 * <p>秒杀是「机会型」提醒 —— 错过就没了，所以不看完也会一直亮。
 * 结束后自动消失（status 变为 2）。
 */
async function refreshSeckillBadge() {
  try {
    const res = await getCurrentActivity()
    const a = res?.data
    seckillOngoing.value = a && a.status === ACTIVITY.RUNNING ? 1 : 0
  } catch (e) {
    // 活动查询失败不代表没有活动，保留原值而不是清零
  }
}

/**
 * 购物车提醒：已失效/下架的商品数。
 *
 * <p><b>为什么不算全部条目</b>：购物车数量本身就是用户自己加的东西，
 * 一直亮着等于噪声。真正需要提醒的是「加进去之后失效了」——
 * 商品下架或库存为 0，那种情况用户需要处理（删掉或换一件）。
 *
 * <p>当前后端购物车接口未返回失效标记，暂以 0 处理，
 * 等后端补 {@code invalidCount} 字段后接上即可，逻辑不用改。
 */
async function refreshCartBadge() {
  try {
    const res = await getCartCount()
    cartCount.value = res?.data || 0
  } catch (e) {
    // 静默：角标是增强功能，不该因它报错打断页面
  }
}

async function refreshBuyerBadges() {
  try {
    const [im, order] = await Promise.all([getImUnread(), getOrderCount()])
    imUnread.value = im?.data?.unread || 0
    const d = order?.data || {}
    // 买家要动手的：待付款 + 待收货。
    // 已付款(1) 不算 —— 等的是商家发货，买家无需动作
    buyerOrderPending.value = (d.status0 || 0) + (d.status2 || 0)
  } catch (e) {
    // 静默
  }
}

async function refreshMerchantBadges() {
  try {
    const [order, afterSale, product] = await Promise.all([
      getMerchantOrderCount(), getAfterSaleCount(), getProductCount()
    ])
    const o = order?.data || {}
    merchantOrderPending.value = (o.status1 || 0) + (o.status2 || 0)
    merchantAfterSalePending.value = (afterSale?.data || {}).status1 || 0
    merchantProductPending.value = product?.data?.pending || 0
  } catch (e) {
    // 静默
  }
}

async function refreshMerchantImBadge() {
  try {
    const res = await getImUnread()
    merchantImUnread.value = res?.data?.unread || 0
  } catch (e) {
    // 静默
  }
}

// ==================== 对外的聚合值 ====================

/** 买家导航栏角标 */
export const buyerBadges = computed(() => ({
  seckill: seckillOngoing.value,
  cart: cartCount.value,
  orders: buyerOrderPending.value,
  messages: imUnread.value
}))

/** 商家工作台角标 */
export const merchantBadges = computed(() => ({
  orders: merchantOrderPending.value,
  afterSale: merchantAfterSalePending.value,
  products: merchantProductPending.value,
  messages: merchantImUnread.value
}))

/** 按角色刷新全部相关角标 */
export async function refreshBadges() {
  const store = useUserStore()
  if (!store.isLogin) {
    resetBadges()
    return
  }
  // 秒杀对所有人生效，不分角色
  await refreshSeckillBadge()

  if (store.isMerchant) {
    await Promise.all([refreshMerchantBadges(), refreshMerchantImBadge()])
  } else if (store.isAdmin) {
    await refreshMerchantBadges()
  } else {
    await Promise.all([refreshBuyerBadges(), refreshCartBadge()])
  }
}

/** 切换账号时清零 —— 否则 A 账号的角标会留给 B 账号看 */
export function resetBadges() {
  imUnread.value = 0
  buyerOrderPending.value = 0
  cartCount.value = 0
  merchantOrderPending.value = 0
  merchantAfterSalePending.value = 0
  merchantProductPending.value = 0
  merchantImUnread.value = 0
  // seckillOngoing 刻意不清 —— 活动状态与账号无关
}

/** 格式化为显示值：超上限显示 99+，0 返回空串（隐藏） */
export function formatBadge(n) {
  const v = Number(n)
  if (!Number.isFinite(v) || v <= 0) return ''
  return v > BADGE_MAX ? `${BADGE_MAX}+` : String(Math.floor(v))
}

/** 是否用纯点形态 */
export function isDot(n) {
  const v = Number(n) || 0
  return v > 0 && v < DOT_THRESHOLD
}

export default {
  buyerBadges,
  merchantBadges,
  refreshBadges,
  refreshCartBadge,
  resetBadges,
  formatBadge,
  isDot,
  BADGE_MAX,
  DOT_THRESHOLD
}