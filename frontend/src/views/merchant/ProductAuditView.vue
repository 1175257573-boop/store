<template>
  <div class="product-audit">
    <div class="page-head"><h2>商品审核</h2></div>

    <div class="filter-bar">
      <el-input v-model="keyword" placeholder="搜索商品名称" clearable
                style="width: 240px" @keyup.enter="loadList" />
      <el-button @click="loadList">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" stripe>
      <el-table-column label="商品" min-width="280">
        <template #default="{ row }">
          <div class="goods-cell">
            <img :src="row.mainImage" class="thumb" />
            <div class="info">
              <div class="name">{{ row.name }}</div>
              <div class="sub">{{ row.subtitle || '无副标题' }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="店铺" width="140">
        <template #default="{ row }">{{ shopName(row.merchantId) }}</template>
      </el-table-column>
      <el-table-column label="价格" width="100">
        <template #default="{ row }"><span class="price">¥{{ row.price }}</span></template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column prop="createTime" label="提交时间" width="160" />
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" size="small" link @click="onAudit(row, true)">通过</el-button>
          <el-button size="small" link @click="onAudit(row, false)">拒绝</el-button>
        </template>
      </el-table-column>
      <template #empty><el-empty description="没有待审核的商品" :image-size="80" /></template>
    </el-table>

    <el-dialog v-model="reasonVisible" :title="pass ? '通过商品审核' : '拒绝商品审核'"
               width="420px">
      <el-input v-model="reason" type="textarea" :rows="3" maxlength="200" show-word-limit
                :placeholder="pass ? '可填写审核意见（选填）' : '请填写拒绝原因'" />
      <template #footer>
        <el-button @click="reasonVisible = false">取消</el-button>
        <el-button :type="pass ? 'danger' : 'warning'" :loading="submitting"
                   @click="submit">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listPendingAudits, auditProduct, listShops } from '@/api/merchant'

const loading = ref(false)
const submitting = ref(false)
const reasonVisible = ref(false)
const list = ref([])
const keyword = ref('')
const shops = ref([])
const pass = ref(true)
const reason = ref('')
const current = ref(null)

function shopName(merchantId) {
  if (!merchantId) return '平台自营'
  const s = shops.value.find(x => x.id === merchantId)
  return s ? s.shopName : `商家 ${merchantId}`
}

async function loadList() {
  loading.value = true
  try {
    const res = await listPendingAudits({ keyword: keyword.value || undefined })
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

function onAudit(row, isPass) {
  current.value = row
  pass.value = isPass
  reason.value = ''
  reasonVisible.value = true
}

async function submit() {
  if (!pass.value && !reason.value.trim()) {
    ElMessage.warning('拒绝必须填写原因')
    return
  }
  submitting.value = true
  try {
    await auditProduct(current.value.id, pass.value, reason.value.trim() || null)
    ElMessage.success(pass.value ? '审核通过，商家可自行上架' : '已拒绝')
    reasonVisible.value = false
    loadList()
    // 通知侧边栏刷新待审红点
    window.dispatchEvent(new Event('todo-changed'))
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

onMounted(() => {
  loadList()
  listShops().then(res => (shops.value = res.data || []))
})
</script>

<style scoped>
.page-head { margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.goods-cell { display: flex; gap: 10px; align-items: center; }
.thumb {
  width: 44px; height: 44px; border-radius: 6px;
  object-fit: cover; background: #f2f3f5; flex-shrink: 0;
}
.goods-cell .name {
  font-size: 13px; margin-bottom: 2px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden;
}
.goods-cell .sub { font-size: 12px; color: var(--ec-text-light); }
</style>
