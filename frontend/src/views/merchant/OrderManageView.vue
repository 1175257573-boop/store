<template>
  <div class="merchant-order">
    <div class="page-head">
      <h2>订单管理</h2>
    </div>

    <el-tabs v-model="activeStatus" @tab-change="loadList">
      <el-tab-pane label="全部" name="-1" />
      <el-tab-pane label="待发货" name="1">
        <template #label>待发货<el-badge v-if="count.status1" :value="count.status1" class="tab-badge" /></template>
      </el-tab-pane>
      <el-tab-pane label="待收货" name="2" />
      <el-tab-pane label="已完成" name="3" />
      <el-tab-pane label="已取消" name="4" />
    </el-tabs>

    <div class="filter-bar">
      <el-input v-model="keyword" placeholder="订单号 / 收货人" clearable
                style="width: 220px" @keyup.enter="loadList" />
      <el-button @click="loadList">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" stripe>
      <el-table-column prop="orderNo" label="订单号" min-width="180" />
      <el-table-column label="收货人" width="140">
        <template #default="{ row }">
          <div>{{ row.receiver }}</div>
          <div class="sub">{{ row.phone }}</div>
        </template>
      </el-table-column>
      <el-table-column prop="address" label="收货地址" min-width="220" show-overflow-tooltip />
      <el-table-column label="金额" width="100">
        <template #default="{ row }">
          <span class="price">¥{{ row.payAmount }}</span>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="物流" width="160">
        <template #default="{ row }">
          <span v-if="row.shipNo">{{ row.shipCompany }} {{ row.shipNo }}</span>
          <span v-else class="sub">-</span>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="100" fixed="right">
        <template #default="{ row }">
          <el-button v-if="row.status === 1" type="danger" size="small" link
                     @click="openShip(row)">发货</el-button>
          <span v-else class="sub">-</span>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无订单" :image-size="80" /></template>
    </el-table>

    <el-dialog v-model="shipVisible" title="订单发货" width="440px">
      <el-form label-width="80px">
        <el-form-item label="订单号">{{ current?.orderNo }}</el-form-item>
        <el-form-item label="收货人">{{ current?.receiver }} {{ current?.phone }}</el-form-item>
        <el-form-item label="快递公司" required>
          <el-select v-model="shipForm.shipCompany" style="width: 100%" placeholder="选择快递">
            <el-option v-for="c in companies" :key="c" :label="c" :value="c" />
          </el-select>
        </el-form-item>
        <el-form-item label="快递单号" required>
          <el-input v-model="shipForm.shipNo" placeholder="请输入单号" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="shipVisible = false">取消</el-button>
        <el-button type="danger" :loading="shipping" @click="doShip">确认发货</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listMerchantOrders, shipOrder, getMerchantOrderCount } from '@/api/merchant'

const loading = ref(false)
const shipping = ref(false)
const shipVisible = ref(false)
const list = ref([])
const keyword = ref('')
const activeStatus = ref('-1')
const count = ref({})
const current = ref(null)
const shipForm = reactive({ shipCompany: '', shipNo: '' })

const companies = ['顺丰速运', '京东物流', '中通快递', '圆通速递', '韵达快递', '申通快递']

function statusText(s) {
  return { 0: '待付款', 1: '待发货', 2: '待收货', 3: '已完成', 4: '已取消' }[s] || '未知'
}
function statusTag(s) {
  return { 0: 'warning', 1: 'warning', 2: 'primary', 3: 'success', 4: 'info' }[s] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMerchantOrders({
      status: activeStatus.value === '-1' ? undefined : Number(activeStatus.value),
      keyword: keyword.value || undefined
    })
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

function openShip(row) {
  current.value = row
  shipForm.shipCompany = ''
  shipForm.shipNo = ''
  shipVisible.value = true
}

async function doShip() {
  if (!shipForm.shipCompany) { ElMessage.warning('请选择快递公司'); return }
  if (!shipForm.shipNo.trim()) { ElMessage.warning('请填写快递单号'); return }
  shipping.value = true
  try {
    await shipOrder(current.value.id, shipForm.shipCompany, shipForm.shipNo.trim())
    ElMessage.success('发货成功')
    shipVisible.value = false
    loadList()
    loadCount()
  } catch (e) { /* 拦截器已提示 */ } finally {
    shipping.value = false
  }
}

async function loadCount() {
  try {
    const res = await getMerchantOrderCount()
    count.value = res.data || {}
  } catch (e) { /* 忽略 */ }
}

onMounted(() => { loadList(); loadCount() })
</script>

<style scoped>
.page-head { margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.sub { font-size: 12px; color: var(--ec-text-light); }
.tab-badge { margin-left: 6px; }
</style>
