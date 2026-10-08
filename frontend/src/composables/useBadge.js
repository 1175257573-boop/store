import { computed, ref } from 'vue'
import { getOrderCount } from '@/api'
import { getImUnread } from '@/api/im'
import {
  getAfterSaleCount,
  getMerchantOrderCount,
  getProductCount
} from '@/api/merchant'
import { useUserStore } from '@/stores/user'

/**
 * 导航栏红点（未读/待处理提醒）的统一状态源。
 *
 * <p><b>为什么集中管理</b>：红点数量来自 5 个不同接口，
 * 若分散在 MainLayout 与各页面里维护，会出现「消息页刷新后购物车角标被清零」
 * 这类互相污染。集中到这里，每个红点只由自己的数据源决定。
 *
 * <p><b>「未读」与「待处理」不是一回事</b>：
 * <ul>
 *   <li>未读（unread）：对方发了消息我还没看 → IM 的未读数</li>
 *   <li>待处理（pending）：业务上等着我做事，不是我没看数据。
 *       买家看待付款/待收货，商家看待发货/售后 —— 都是需要动作的</li>
 * </ul>
 * 把「已完成」也算进红点会让角标永远是脏的。
 *
 * <p><b>清除时机</b>：红点由服务端数据决定，<b>不做本地清零</b>。
 * 前端本地清零会有两个问题：
 * <ol>
 *   <li>刷新页面就回来了 —— 用户会以为没清掉</li>
 *   <li>跨设备/多标签页不一致</li>
 * </ol>
 * 「进入页面即清除」的语义由后端保证：
 * 拉消息会顺带 markRead，看订单列表后计数自然变化。
 * 前端只负责刷新，不负责抹掉。
 */

// ==================== 状态 ====================

/** 买家侧：IM 未读消息数 */
const imUnread = ref(0)
/** 买家侧：待处理订单数（待付款 + 待收货） */
const buyerOrderPending = ref(0)
/** 商家侧：待发货订单数 */
const merchantOrderPending = ref(0)
/** 商家侧：待处理售后数 */
const merchantAfterSalePending = ref(0)
/** 商家侧：待审核商品数 */
const merchantProductPending = ref(0)
/** 商家侧：买家咨询未读数（IM 的商家视角） */
const merchantImUnread = ref(0)

// ==================== 状态常量 ====================

/**
 * 订单状态（对齐后端 BizConst）。
 * <p>红点只算「等着我动作」的状态：已付款等着我确认收货，已完成/已取消不算。
 */
const ORDER = {
  UNPAID: 0,     // 待付款
  PAID: 1,       // 已付款 → 买家待收货、商家待发货
  SHIPPED: 2,    // 已发货 → 买家待收货
  FINISHED: 3,
  CANCELLED: 4
}

// ==================== 刷新 ====================

/**
 * 拉取买家侧红点。
 *
 * <p>失败时**保持原值不清零** —— 网络抖动不该让红点突然消失，
 * 用户会以为漏了消息。
 */
async function refreshBuyerBadges() {
  try {
    const [im, order] = await Promise.all([
      getImUnread(),
      getOrderCount()
    ])
    imUnread.value = im?.data?.unread || 0
    const d = order?.data || {}
    // 买家要动手的：待付款(0) + 已发货待收货(2)
    // 已付款(1) 对买家是「等发货」，不必提醒；已发货(2) 才需要确认收货
    buyerOrderPending.value = (d.status0 || 0) + (d.status2 || 0)
  } catch (e) {
    // 静默失败：红点是增强功能，不该因它报错打断页面
  }
}

/** 拉取商家侧红点。 */
async function refreshMerchantBadges() {
  try {
    const [order, afterSale, product] = await Promise.all([
      getMerchantOrderCount(),
      getAfterSaleCount(),
      getProductCount()
    ])
    const o = order?.data || {}
    // 商家要动手的：已付款待发货(1) + 已发货待收货(2)
    merchantOrderPending.value = (o.status1 || 0) + (o.status2 || 0)
    const a = afterSale?.data || {}
    // 售后 status1 = 处理中
    merchantAfterSalePending.value = a.status1 || 0
    merchantProductPending.value = product?.data?.pending || 0
  } catch (e) {
    // 同上
  }
}

/**
 * 商家侧的 IM 未读。
 *
 * <p>接口与买家共用 {@code /im/unread}，后端按登录角色自动返回
 * 商家维度的未读数（商家看到的是买家发给自己的）。
 */
async function refreshMerchantImBadge() {
  try {
    const res = await getImUnread()
    merchantImUnread.value = res?.data?.unread || 0
  } catch (e) {
    // 静默
  }
}

// ==================== 对外的聚合值 ====================

/**
 * 买家导航栏各红点。
 *
 * <p>购物车不放这里 —— 它是「数量」而非「提醒」，
 * 语义不同（购物车角标是用户自己加的东西），混在提醒里会误导。
 */
export const buyerBadges = computed(() => ({
  messages: imUnread.value,
  orders: buyerOrderPending.value
}))

/** 商家工作台红点。 */
export const merchantBadges = computed(() => ({
  orders: merchantOrderPending.value,
  afterSale: merchantAfterSalePending.value,
  products: merchantProductPending.value,
  messages: merchantImUnread.value
}))

/** 按角色刷新全部相关红点。 */
export async function refreshBadges() {
  const store = useUserStore()
  if (!store.isLogin) {
    imUnread.value = 0
    buyerOrderPending.value = 0
    return
  }
  if (store.isMerchant) {
    await Promise.all([refreshMerchantBadges(), refreshMerchantImBadge()])
  } else if (store.isAdmin) {
    await Promise.all([refreshMerchantBadges()])
  } else {
    await refreshBuyerBadges()
  }
}

/**
 * 切换账号时清零。
 *
 * <p>必须清 —— 否则 A 账号的红点会留在页面上给 B 账号看，
 * 是数据泄露的一种。
 */
export function resetBadges() {
  imUnread.value = 0
  buyerOrderPending.value = 0
  merchantOrderPending.value = 0
  merchantAfterSalePending.value = 0
  merchantProductPending.value = 0
  merchantImUnread.value = 0
}

/**
 * 是否展示纯红点（无数字时）。
 *
 * <p>用于「有待处理但数量不值得显示」的场景，比如管理员待审核数 > 0 但只有 1 条时
 * 显示纯点更干净。数字 ≥ 10 时角标会变宽，纯点反而不够醒目。
 */
export function asDot(n) {
  return n > 0 && n < 10 ? '' : String(n)
}

export default {
  imUnread,
  buyerOrderPending,
  merchantOrderPending,
  merchantAfterSalePending,
  merchantProductPending,
  merchantImUnread,
  buyerBadges,
  merchantBadges,
  refreshBadges,
  refreshBuyerBadges,
  refreshMerchantBadges,
  resetBadges,
  asDot
}