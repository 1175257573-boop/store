<template>
  <div class="after-sale">
    <div class="page-head"><h2>售后处理</h2></div>

    <el-tabs v-model="activeStatus" @tab-change="loadList">
      <el-tab-pane label="全部" name="-1" />
      <el-tab-pane label="待处理" name="0">
        <template #label>待处理<el-badge v-if="counts.status0" :value="counts.status0" class="tab-badge" /></template>
      </el-tab-pane>
      <el-tab-pane label="已同意" name="1" />
      <el-tab-pane label="已拒绝" name="2" />
      <el-tab-pane label="已完成" name="3" />
    </el-tabs>

    <el-table v-loading="loading" :data="list" size="small" stripe>
      <el-table-column prop="saleNo" label="售后单号" min-width="170" />
      <el-table-column prop="productName" label="商品" min-width="160" show-overflow-tooltip />
      <el-table-column label="类型" width="90">
        <template #default="{ row }">{{ row.type === 2 ? '退货退款' : '仅退款' }}</template>
      </el-table-column>
      <el-table-column prop="quantity" label="数量" width="70" />
      <el-table-column label="退款金额" width="100">
        <template #default="{ row }"><span class="price">¥{{ row.amount }}</span></template>
      </el-table-column>
      <el-table-column prop="reason" label="原因" width="100" show-overflow-tooltip />
      <el-table-column label="状态" width="110">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">{{ statusText(row.status) }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="170" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button type="danger" size="small" link @click="onApprove(row)">同意</el-button>
            <el-button size="small" link @click="onReject(row)">拒绝</el-button>
          </template>
          <el-button v-else-if="row.status === 1" type="success" size="small" link
                     @click="onComplete(row)">完成退款</el-button>
          <span v-else class="sub">已处理</span>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无售后申请" :image-size="80" /></template>
    </el-table>

    <el-dialog v-model="remarkVisible" :title="remarkAction === 'approve' ? '同意售后' : '拒绝售后'"
               width="420px">
      <el-input v-model="remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                :placeholder="remarkAction === 'approve' ? '可填写处理说明（选填）' : '请填写拒绝原因'" />
      <template #footer>
        <el-button @click="remarkVisible = false">取消</el-button>
        <el-button :type="remarkAction === 'approve' ? 'danger' : 'warning'"
                   :loading="submitting" @click="submitRemark">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  listMerchantAfterSales, approveAfterSale, rejectAfterSale,
  completeRefund, getAfterSaleCount
} from '@/api/merchant'

const loading = ref(false)
const submitting = ref(false)
const list = ref([])
const counts = ref({})
const activeStatus = ref('-1')
const remarkVisible = ref(false)
const remark = ref('')
const remarkAction = ref('approve')
const current = ref(null)

function statusText(s) {
  return { 0: '待处理', 1: '待退款', 2: '已拒绝', 3: '已完成', 4: '已撤销' }[s] || '未知'
}
function statusTag(s) {
  return { 0: 'warning', 1: 'primary', 2: 'info', 3: 'success', 4: 'info' }[s] || 'info'
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMerchantAfterSales(
      activeStatus.value === '-1' ? undefined : Number(activeStatus.value))
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

async function loadCount() {
  try {
    const res = await getAfterSaleCount()
    counts.value = res.data || {}
  } catch (e) { /* 忽略 */ }
}

function onApprove(row) {
  current.value = row
  remarkAction.value = 'approve'
  remark.value = ''
  remarkVisible.value = true
}

function onReject(row) {
  current.value = row
  remarkAction.value = 'reject'
  remark.value = ''
  remarkVisible.value = true
}

async function submitRemark() {
  if (remarkAction.value === 'reject' && !remark.value.trim()) {
    ElMessage.warning('拒绝售后必须填写原因')
    return
  }
  submitting.value = true
  try {
    if (remarkAction.value === 'approve') {
      await approveAfterSale(current.value.id, remark.value.trim() || null)
    } else {
      await rejectAfterSale(current.value.id, remark.value.trim())
    }
    ElMessage.success('处理完成')
    remarkVisible.value = false
    loadList()
    loadCount()
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

async function onComplete(row) {
  try {
    await ElMessageBox.confirm(
      `确认为「${row.productName}」完成退款 ¥${row.amount}？完成后库存将回补。`,
      '确认退款', { type: 'warning' })
  } catch { return }
  try {
    await completeRefund(row.id)
    ElMessage.success('退款已完成')
    loadList()
    loadCount()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(() => { loadList(); loadCount() })
</script>

<style scoped>
.page-head { margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.sub { font-size: 12px; color: var(--ec-text-light); }
.tab-badge { margin-left: 6px; }
</style>
