<template>
  <div v-loading="loading" class="dashboard">
    <div class="page-head">
      <h2>经营概览</h2>
      <el-radio-group v-model="trendDays" size="small" @change="loadTrend">
        <el-radio-button :value="7">近 7 天</el-radio-button>
        <el-radio-button :value="30">近 30 天</el-radio-button>
        <el-radio-button :value="90">近 90 天</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 核心指标卡 -->
    <div class="metric-grid">
      <div class="metric-card">
        <div class="label">今日销售额</div>
        <div class="value">¥{{ overview.todaySales || 0 }}</div>
        <div class="sub">
          <span v-if="overview.salesGrowthRate != null"
                :class="overview.salesGrowthRate >= 0 ? 'up' : 'down'">
            <el-icon><component :is="growthIcon" /></el-icon>
            {{ Math.abs(overview.salesGrowthRate) }}%
          </span>
          <span v-else class="muted">较昨日 —</span>
          <span class="sep">·</span>
          <span>{{ overview.todayOrders || 0 }} 单</span>
        </div>
      </div>

      <div class="metric-card">
        <div class="label">昨日销售额</div>
        <div class="value">¥{{ overview.yesterdaySales || 0 }}</div>
        <div class="sub muted">环比参照</div>
      </div>

      <div class="metric-card">
        <div class="label">本月销售额</div>
        <div class="value">¥{{ overview.monthSales || 0 }}</div>
        <div class="sub muted">累计 ¥{{ overview.totalSales || 0 }}</div>
      </div>

      <div class="metric-card">
        <div class="label">累计订单</div>
        <div class="value">{{ overview.totalOrders || 0 }}</div>
        <div class="sub muted">店铺评分 {{ overview.score || 5.0 }}</div>
      </div>
    </div>

    <!-- 运营指标 -->
    <div class="stat-row">
      <div class="stat">
        <span class="num">{{ overview.productOnShelf || 0 }}</span>
        <span class="txt">在售商品</span>
      </div>
      <div class="stat">
        <span class="num">{{ overview.productTotal || 0 }}</span>
        <span class="txt">全部商品</span>
      </div>
      <div class="stat">
        <span class="num warn">{{ overview.productPendingAudit || 0 }}</span>
        <span class="txt">待审核</span>
      </div>
      <div class="stat">
        <span class="num danger">{{ overview.pendingAfterSale || 0 }}</span>
        <span class="txt">待处理售后</span>
      </div>
    </div>

    <!-- 销售趋势 -->
    <div class="card-box">
      <h3>销售趋势</h3>
      <div style="position: relative; width: 100%; height: 280px;">
        <canvas id="trendChart" role="img" aria-label="近 N 天每日销售额折线图"></canvas>
      </div>
    </div>

    <!-- 商品排行 -->
    <div class="card-box">
      <h3>商品销量排行 TOP 10</h3>
      <el-table v-if="topList.length" :data="topList" size="small">
        <el-table-column type="index" label="#" width="50" />
        <el-table-column prop="productName" label="商品名称" min-width="200" />
        <el-table-column prop="quantity" label="销量" width="100" />
        <el-table-column label="销售额" width="140">
          <template #default="{ row }">
            <span class="price">¥{{ row.amount }}</span>
          </template>
        </el-table-column>
      </el-table>
      <el-empty v-else description="暂无销量数据" :image-size="80" />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, nextTick } from 'vue'
import { Top, Bottom } from '@element-plus/icons-vue'
import { computed } from 'vue'
import { getDashboardOverview, getSalesTrend, getTopProducts } from '@/api/merchant'

const loading = ref(false)

/** 涨跌方向对应的图标 */
const growthIcon = computed(() =>
  overview.value.salesGrowthRate >= 0 ? Top : Bottom)
const overview = ref({})
const topList = ref([])
const trendDays = ref(30)
let chart = null

async function loadOverview() {
  loading.value = true
  try {
    const res = await getDashboardOverview()
    overview.value = res.data || {}
  } catch (e) {
    // 拦截器已提示
  } finally {
    loading.value = false
  }
}

async function loadTop() {
  try {
    const res = await getTopProducts(10)
    topList.value = res.data?.list || []
  } catch (e) { /* 忽略 */ }
}

async function loadTrend() {
  try {
    const res = await getSalesTrend(trendDays.value)
    await nextTick()
    renderChart(res.data)
  } catch (e) { /* 忽略 */ }
}

function renderChart(data) {
  // 引入 CDN 版 Chart.js，商家端只有这一个图表，不值得打包进主 chunk
  if (typeof Chart === 'undefined') {
    const script = document.createElement('script')
    script.src = 'https://cdn.jsdelivr.net/npm/chart.js@4.4.1/dist/chart.umd.min.js'
    script.onload = () => drawChart(data)
    document.head.appendChild(script)
  } else {
    drawChart(data)
  }
}

function drawChart(data) {
  const ctx = document.getElementById('trendChart')
  if (!ctx) return
  if (chart) chart.destroy()
  chart = new Chart(ctx, {
    type: 'line',
    data: {
      labels: data.dates || [],
      datasets: [{
        label: '每日销售额',
        data: (data.amounts || []).map(Number),
        borderColor: '#e4393c',
        backgroundColor: 'rgba(228, 57, 60, 0.08)',
        fill: true,
        tension: 0.3,
        pointRadius: 0,
        pointHoverRadius: 4
      }]
    },
    options: {
      responsive: true,
      maintainAspectRatio: false,
      plugins: {
        legend: { display: false },
        tooltip: {
          callbacks: {
            label: (ctx) => '¥' + Number(ctx.parsed.y).toFixed(2)
          }
        }
      },
      scales: {
        y: {
          beginAtZero: true,
          ticks: { callback: (v) => '¥' + v }
        },
        x: { ticks: { maxTicksLimit: 12 } }
      }
    }
  })
}

onMounted(() => {
  loadOverview()
  loadTop()
  loadTrend()
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 18px;
}
.page-head h2 {
  margin: 0;
  font-size: 18px;
}

.metric-grid {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 12px;
  margin-bottom: 16px;
}
@media (max-width: 900px) {
  .metric-grid { grid-template-columns: repeat(2, 1fr); }
}

.metric-card {
  background: var(--color-background-secondary, #fafafa);
  border-radius: var(--border-radius-md, 8px);
  padding: 16px;
}
.metric-card .label {
  font-size: 13px;
  color: var(--ec-text-light);
  margin-bottom: 6px;
}
.metric-card .value {
  font-size: 24px;
  font-weight: 500;
  color: var(--ec-primary);
  margin-bottom: 4px;
}
.metric-card .sub {
  font-size: 12px;
  color: var(--ec-text-light);
  display: flex;
  align-items: center;
  gap: 4px;
}
.metric-card .sub .up { color: #67c23a; }
.metric-card .sub .down { color: var(--ec-primary); }
.muted { color: #b0b0b0; }
.sep { margin: 0 2px; }

.stat-row {
  display: flex;
  gap: 24px;
  padding: 14px 0;
  border-bottom: 1px solid var(--ec-border);
  margin-bottom: 16px;
  flex-wrap: wrap;
}
.stat {
  display: flex;
  align-items: baseline;
  gap: 6px;
}
.stat .num {
  font-size: 20px;
  font-weight: 500;
}
.stat .num.warn { color: #e6a23c; }
.stat .num.danger { color: var(--ec-primary); }
.stat .txt { font-size: 13px; color: var(--ec-text-light); }

.card-box {
  border-top: 1px solid var(--ec-border);
  padding-top: 16px;
  margin-top: 16px;
}
.card-box h3 {
  font-size: 15px;
  margin: 0 0 12px;
}
</style>
