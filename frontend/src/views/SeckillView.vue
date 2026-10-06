<template>
  <div class="seckill container">
    <!-- 活动头部 -->
    <div v-if="activity" class="hero">
      <div class="hero-bg"></div>
      <div class="hero-content">
        <el-tag type="danger" effect="dark" size="small">限时秒杀</el-tag>
        <h1>{{ activity.name }}</h1>
        <div class="hero-meta">
          <span class="stock-num">{{ remaining }}</span>
          <span class="stock-label">件库存</span>
        </div>
        <div class="countdown">
          <span>距结束</span>
          <strong>{{ countdown }}</strong>
        </div>
      </div>
    </div>

    <el-empty v-else-if="!loading" description="当前没有进行中的秒杀活动" />

    <!-- 商品网格 -->
    <template v-if="activity">
      <h2 class="section-title">抢购商品</h2>
      <div class="grid">
        <div v-for="item in goods" :key="item.skuId" class="goods-card">
          <img :src="item.image" :alt="item.name" class="cover" />
          <div class="info">
            <div class="name">{{ item.name }}</div>
            <div class="price-row">
              <span class="price">
                <span class="price-symbol">¥</span>
                <span class="price-value">{{ item.price }}</span>
              </span>
            </div>
            <div class="stock-bar">
              <div class="bar">
                <div class="fill" :style="{ width: soldPercent(item) + '%' }"></div>
              </div>
              <span class="stock-text">
                已抢 {{ item.sold }} / {{ item.total }}
              </span>
            </div>
            <el-button
              type="danger"
              class="seckill-btn"
              :loading="loadingSku === item.skuId"
              :disabled="item.total - item.sold <= 0"
              @click="onSeckill(item)"
            >
              {{ item.total - item.sold <= 0 ? '已售罄' : '立即抢购' }}
            </el-button>
          </div>
        </div>
      </div>

      <!-- 我的秒杀订单 -->
      <h2 class="section-title">我的秒杀订单</h2>
      <div v-loading="loadingOrders" class="card-box">
        <el-table v-if="myOrders.length" :data="myOrders" size="small" stripe>
          <el-table-column prop="orderNo" label="订单号" min-width="200" />
          <el-table-column label="商品" min-width="200">
            <template #default="{ row }">
              {{ goodsName(row.skuId) }}
            </template>
          </el-table-column>
          <el-table-column prop="quantity" label="数量" width="70" />
          <el-table-column label="金额" width="110">
            <template #default="{ row }">
              <span class="price">¥{{ row.amount }}</span>
            </template>
          </el-table-column>
          <el-table-column label="状态" width="100">
            <template #default="{ row }">
              <el-tag :type="statusTag(row.status)" size="small">
                {{ statusText(row.status) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="160">
            <template #default="{ row }">
              <el-button v-if="row.status === 0" type="danger" size="small" link
                         @click="onPay(row)">支付</el-button>
              <el-button v-if="row.status === 0" size="small" link
                         @click="onCancel(row)">取消</el-button>
            </template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loadingOrders" description="还没有秒杀订单" :image-size="80" />
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted, onUnmounted } from 'vue'
import { ElMessage } from 'element-plus'
import {
  seckill,
  querySeckillResult,
  paySeckillOrder,
  cancelSeckillOrder,
  getMySeckillOrders,
  getCurrentActivity
} from '@/api/seckill'
import { useUserStore } from '@/stores/user'
import { useRouter } from 'vue-router'

const router = useRouter()
const userStore = useUserStore()

const loading = ref(false)
const loadingOrders = ref(false)
const loadingSku = ref(null)
const activity = ref(null)
const myOrders = ref([])
const remaining = ref(0)
let timer = null

// 商品静态信息（真实项目应从商品接口取）
const goodsMeta = {
  1: { name: 'Apple iPhone 16 Pro 256GB', price: 7999, image: 'https://picsum.photos/seed/iphone16pro/600/600' },
  3: { name: '小米 15 Pro 16GB+512GB', price: 5299, image: 'https://picsum.photos/seed/xiaomi15pro/600/600' },
  9: { name: '戴森 V12 Detect Slim', price: 3699, image: 'https://picsum.photos/seed/dysonv12/600/600' }
}

const goods = computed(() => {
  if (!activity.value) return []
  const detail = stockDetail.value || {}
  return [
    { skuId: 1, ...goodsMeta[1] },
    { skuId: 3, ...goodsMeta[3] },
    { skuId: 9, ...goodsMeta[9] }
  ].map(g => {
    const total = detail.totalStock || 1000
    const sold = total - (detail.available ?? total)
    return { ...g, total, sold: Math.max(sold, 0) }
  })
})

const stockDetail = ref(null)

const countdown = ref('--:--:--')
const endTime = computed(() => activity.value?.endTime
  ? new Date(activity.value.endTime.replace(/-/g, '/')) : null)

function soldPercent(item) {
  if (!item.total) return 0
  return Math.min(100, Math.round((item.sold / item.total) * 100))
}

function goodsName(skuId) {
  return goodsMeta[skuId]?.name || `SKU ${skuId}`
}

function statusText(s) {
  return { 0: '待支付', 1: '已支付', 2: '已取消', 3: '已关闭' }[s] || '未知'
}
function statusTag(s) {
  return { 0: 'warning', 1: 'success', 2: 'info', 3: 'info' }[s] || 'info'
}

function tick() {
  const end = endTime.value
  if (!end) return
  const diff = end.getTime() - Date.now()
  if (diff <= 0) {
    countdown.value = '已结束'
    return
  }
  const h = String(Math.floor(diff / 3600000)).padStart(2, '0')
  const m = String(Math.floor((diff % 3600000) / 60000)).padStart(2, '0')
  const s = String(Math.floor((diff % 60000) / 1000)).padStart(2, '0')
  countdown.value = `${h}:${m}:${s}`
}

async function loadActivity() {
  loading.value = true
  try {
    const res = await getCurrentActivity()
    activity.value = res.data
    if (activity.value) {
      tick()
      timer = setInterval(tick, 1000)
    }
  } catch (e) {
    activity.value = null
  } finally {
    loading.value = false
  }
}

async function loadOrders() {
  if (!userStore.isLogin) return
  loadingOrders.value = true
  try {
    const res = await getMySeckillOrders()
    myOrders.value = res.data || []
  } catch (e) {
    myOrders.value = []
  } finally {
    loadingOrders.value = false
  }
}

async function onSeckill(item) {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push({ path: '/login', query: { redirect: '/seckill' } })
    return
  }
  loadingSku.value = item.skuId
  // 请求唯一 ID：同一次点击只生成一次，重复提交时后端可据此识别
  const requestId = `req-${userStore.userInfo?.userId}-${item.skuId}-${Date.now()}`
  try {
    const res = await seckill({
      activityId: activity.value.id,
      skuId: item.skuId,
      requestId,
      quantity: 1
    })
    const r = res.data
    if (r.code === 2) {
      ElMessage.warning('手慢了，商品已售罄')
    } else if (r.code === 3) {
      ElMessage.info('您已参与过本次活动')
    } else if (r.code === 0) {
      remaining.value = r.remaining ?? remaining.value
      ElMessage.success('抢购请求已提交，正在排队')
      // 轮询查询最终结果：异步落单需要时间
      pollResult(requestId)
    } else {
      ElMessage.info(r.message)
    }
  } catch (e) {
    // 提示已由拦截器处理
  } finally {
    loadingSku.value = null
  }
}

/**
 * 轮询抢购结果。
 * 后端是异步落单，提交后需要等消费端处理完才知道成没成功。
 */
async function pollResult(requestId, times = 10) {
  for (let i = 0; i < times; i++) {
    await new Promise(r => setTimeout(r, 500))
    try {
      const res = await querySeckillResult(requestId)
      if (res.data?.code === 1) {
        ElMessage.success('抢购成功！请尽快完成支付')
        loadOrders()
        return
      }
    } catch (e) {
      return
    }
  }
}

async function onPay(row) {
  try {
    await paySeckillOrder(row.id)
    ElMessage.success('支付成功')
    loadOrders()
  } catch (e) { /* 提示已处理 */ }
}

async function onCancel(row) {
  try {
    await cancelSeckillOrder(row.id)
    ElMessage.success('订单已取消，库存已释放')
    loadOrders()
  } catch (e) { /* 提示已处理 */ }
}

onMounted(() => {
  loadActivity()
  loadOrders()
})
onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<style scoped>
.hero {
  margin: 20px 0;
  border-radius: var(--ec-radius);
  overflow: hidden;
  position: relative;
  min-height: 200px;
  display: flex;
  align-items: center;
  padding: 0 40px;
}
.hero-bg {
  position: absolute;
  inset: 0;
  background: linear-gradient(120deg, #e4393c 0%, #8b1a1c 100%);
}
.hero-content {
  position: relative;
  color: #fff;
}
.hero-content h1 {
  margin: 10px 0 14px;
  font-size: 26px;
}
.hero-meta {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.stock-num {
  font-size: 40px;
  font-weight: 700;
  font-family: 'DIN Alternate', Arial, sans-serif;
}
.stock-label {
  font-size: 14px;
  opacity: 0.9;
}
.countdown {
  margin-top: 12px;
  font-size: 13px;
  opacity: 0.92;
}
.countdown strong {
  font-family: 'DIN Alternate', monospace;
  font-size: 15px;
  margin-left: 6px;
}

.goods-card {
  background: #fff;
  border-radius: var(--ec-radius);
  overflow: hidden;
  display: flex;
  flex-direction: column;
}
.cover {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  background: #f2f3f5;
}
.info {
  padding: 14px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.name {
  font-size: 14px;
  line-height: 1.4;
  min-height: 39px;
}
.stock-bar {
  display: flex;
  flex-direction: column;
  gap: 4px;
}
.bar {
  height: 6px;
  background: #f0f0f0;
  border-radius: 3px;
  overflow: hidden;
}
.fill {
  height: 100%;
  background: var(--ec-primary);
  border-radius: 3px;
  transition: width 0.3s;
}
.stock-text {
  font-size: 12px;
  color: var(--ec-text-light);
}
.seckill-btn {
  width: 100%;
  font-weight: 600;
}
</style>
