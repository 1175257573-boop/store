<template>
  <div class="seckill-admin">
    <div class="page-head">
      <h2>秒杀活动</h2>
      <div>
        <el-button :loading="loading" @click="loadList">
          <el-icon><Refresh /></el-icon>刷新
        </el-button>
        <el-button type="danger" @click="openDialog()">
          <el-icon><Plus /></el-icon>发布活动
        </el-button>
      </div>
    </div>

    <el-alert
      v-if="isAdmin"
      type="info"
      :closable="false"
      show-icon
      class="mb12"
      title="当前为平台管理员"
      description="你发布的活动归属平台，所有商家都能看到；商家只能看到并管理自己的活动。"
    />

    <div class="filter-bar">
      <el-input
        v-model="query.keyword"
        placeholder="活动名称 / 编号"
        clearable
        style="width: 220px"
        @keyup.enter="loadList"
      />
      <el-select v-model="query.status" placeholder="状态" clearable
                 style="width: 120px" @change="loadList">
        <el-option label="进行中" :value="1" />
        <el-option label="未开始" :value="0" />
        <el-option label="已结束" :value="2" />
        <el-option label="已取消" :value="3" />
      </el-select>
      <el-button @click="loadList">查询</el-button>
      <el-button link @click="resetQuery">重置</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" stripe>
      <el-table-column label="活动" min-width="260">
        <template #default="{ row }">
          <div class="activity-cell">
            <img v-if="row.coverImage" :src="row.coverImage" class="cover" />
            <div v-else class="cover placeholder">秒杀</div>
            <div class="info">
              <div class="name">{{ row.name }}</div>
              <div class="sub">{{ row.activityNo }}</div>
            </div>
          </div>
        </template>
      </el-table-column>
      <el-table-column label="归属" width="120">
        <template #default="{ row }">
          <el-tag v-if="!row.merchantId" type="warning" size="small">平台自建</el-tag>
          <span v-else class="sub">商家 #{{ row.merchantId }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="totalStock" label="总库存" width="80" />
      <el-table-column prop="bucketCount" label="分桶" width="70" />
      <el-table-column label="限购" width="70">
        <template #default="{ row }">{{ row.limitPerUser }} 件</template>
      </el-table-column>
      <el-table-column label="活动时间" width="270">
        <template #default="{ row }">
          <div class="sub">{{ formatTime(row.startTime) }}</div>
          <div class="sub">至 {{ formatTime(row.endTime) }}</div>
        </template>
      </el-table-column>
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="statusTag(row.status)" size="small">
            {{ statusText(row.status) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="230" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openDetail(row)">详情</el-button>
          <el-button v-if="row.status === 1" link @click="onOffline(row)">下线</el-button>
          <el-button v-else-if="row.status !== 3" link type="success"
                     @click="onOnline(row)">上线</el-button>
          <el-button link @click="onResetStock(row)">重置库存</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有秒杀活动" :image-size="80">
          <el-button type="danger" @click="openDialog()">发布第一场活动</el-button>
        </el-empty>
      </template>
    </el-table>

    <!-- 发布 / 编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑秒杀活动' : '发布秒杀活动'"
      width="760px"
      :close-on-click-modal="false"
      destroy-on-close
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="100px">
        <el-form-item label="活动名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit
                    placeholder="如：双11限量秒杀" />
        </el-form-item>
        <el-form-item label="活动封面">
          <el-input v-model="form.coverImage" placeholder="图片 URL（选填）" />
        </el-form-item>
        <el-form-item label="活动说明">
          <el-input v-model="form.description" type="textarea" :rows="2" maxlength="500"
                    show-word-limit placeholder="展示给用户的活动介绍" />
        </el-form-item>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="开始时间" prop="startTime" label-width="80px">
              <el-date-picker
                v-model="form.startTime"
                type="datetime"
                placeholder="选择开始时间"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="结束时间" prop="endTime" label-width="80px">
              <el-date-picker
                v-model="form.endTime"
                type="datetime"
                placeholder="选择结束时间"
                style="width: 100%"
              />
            </el-form-item>
          </el-col>
        </el-row>

        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="每人限购" label-width="80px">
              <el-input-number v-model="form.limitPerUser" :min="1" :max="10"
                               style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="库存分桶" label-width="80px">
              <el-input-number v-model="form.bucketCount" :min="1" :max="100"
                               style="width: 100%" />
              <div class="tip">
                拆桶打散 Redis 热点，QPS 越高桶越多
              </div>
            </el-form-item>
          </el-col>
        </el-row>

        <el-form-item label="活动商品">
          <div class="goods-editor">
            <div v-for="(g, gi) in form.goods" :key="gi" class="goods-row">
              <el-select v-model="g.productId" placeholder="选择商品" filterable
                         style="flex: 1; min-width: 200px" @change="onProductChange(g)">
                <el-option v-for="p in products" :key="p.id" :label="p.name" :value="p.id" />
              </el-select>
              <el-input-number v-model="g.price" :min="0.01" :precision="2" :step="10"
                               placeholder="秒杀价" style="width: 130px" />
              <el-input-number v-model="g.totalStock" :min="1" :step="10"
                               placeholder="秒杀库存" style="width: 130px" />
              <el-button link type="danger" @click="form.goods.splice(gi, 1)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <el-button link type="primary" @click="addGoods">
              <el-icon><Plus /></el-icon>添加商品
            </el-button>
            <div class="tip">
              合计库存 {{ totalStock }} 件，会按分桶数平均拆到 Redis 不同 key
            </div>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="onSubmit">
          {{ form.id ? '保存修改' : '确认发布' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- 活动详情 -->
    <el-dialog v-model="detailVisible" title="活动详情" width="700px">
      <template v-if="detail">
        <el-descriptions :column="2" border size="small" class="mb12">
          <el-descriptions-item label="活动名称">
            {{ detail.activity.name }}
          </el-descriptions-item>
          <el-descriptions-item label="活动编号">
            {{ detail.activity.activityNo }}
          </el-descriptions-item>
          <el-descriptions-item label="总库存">
            {{ detail.activity.totalStock }}
          </el-descriptions-item>
          <el-descriptions-item label="分桶数">
            {{ detail.activity.bucketCount }}
          </el-descriptions-item>
          <el-descriptions-item label="每人限购">
            {{ detail.activity.limitPerUser }} 件
          </el-descriptions-item>
          <el-descriptions-item label="状态">
            <el-tag :type="statusTag(detail.activity.status)" size="small">
              {{ statusText(detail.activity.status) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="开始时间" :span="2">
            {{ formatTime(detail.activity.startTime) }}
          </el-descriptions-item>
          <el-descriptions-item label="结束时间" :span="2">
            {{ formatTime(detail.activity.endTime) }}
          </el-descriptions-item>
        </el-descriptions>

        <h4 class="detail-title">商品库存</h4>
        <el-table :data="detail.goods" size="small" border>
          <el-table-column prop="name" label="商品" min-width="200" show-overflow-tooltip />
          <el-table-column prop="price" label="秒杀价" width="100" />
          <el-table-column prop="total_stock" label="总库存" width="90" />
          <el-table-column prop="available" label="可用" width="80" />
        </el-table>

        <h4 class="detail-title">实时库存汇总</h4>
        <div class="stock-summary">
          <div class="sum-item">
            <span class="num">{{ detail.stockSummary.available }}</span>
            <span class="txt">可用</span>
          </div>
          <div class="sum-item">
            <span class="num">{{ detail.stockSummary.locked }}</span>
            <span class="txt">锁定</span>
          </div>
          <div class="sum-item">
            <span class="num">{{ detail.stockSummary.sold }}</span>
            <span class="txt">已售</span>
          </div>
          <div class="sum-item">
            <span class="num total">{{ detail.stockSummary.total }}</span>
            <span class="txt">总计</span>
          </div>
        </div>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete, Refresh } from '@element-plus/icons-vue'
import {
  listActivities,
  publishActivity,
  updateActivity,
  getActivityDetail,
  changeActivityStatus,
  deleteActivity,
  resetActivityStock
} from '@/api/seckillAdmin'
import { getCategories } from '@/api'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()
const isAdmin = computed(() => userStore.userInfo?.role === 2)

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const detailVisible = ref(false)
const list = ref([])
const detail = ref(null)
const products = ref([])
const formRef = ref()

const query = reactive({ keyword: '', status: null })

const emptyForm = () => ({
  id: null,
  name: '',
  coverImage: '',
  description: '',
  startTime: '',
  endTime: '',
  limitPerUser: 1,
  bucketCount: 10,
  goods: [{ productId: null, price: 1, totalStock: 100 }]
})
const form = reactive(emptyForm())

const rules = {
  name: [{ required: true, message: '请输入活动名称', trigger: 'blur' }],
  startTime: [{ required: true, message: '请选择开始时间', trigger: 'change' }],
  endTime: [{ required: true, message: '请选择结束时间', trigger: 'change' }]
}

const totalStock = computed(() =>
  form.goods.reduce((s, g) => s + (Number(g.totalStock) || 0), 0))

function statusText(s) {
  return { 0: '未开始', 1: '进行中', 2: '已结束', 3: '已取消' }[s] || '未知'
}
function statusTag(s) {
  return { 0: 'info', 1: 'success', 2: '', 3: 'danger' }[s] || 'info'
}
function formatTime(t) {
  return t ? String(t).replace('T', ' ').slice(0, 19) : '-'
}

async function loadList() {
  loading.value = true
  try {
    const res = await listActivities({
      keyword: query.keyword || undefined,
      status: query.status ?? undefined
    })
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.keyword = ''
  query.status = null
  loadList()
}

function openDialog() {
  Object.assign(form, emptyForm())
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function addGoods() {
  form.goods.push({ productId: null, price: 1, totalStock: 100 })
}

/** 选商品后自动带出原价作为秒杀价，减少手工输入 */
function onProductChange(g) {
  const p = products.value.find(x => x.id === g.productId)
  if (p && (!g.price || g.price === 1)) {
    g.price = Number(p.price)
  }
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (!form.goods.length || form.goods.some(g => !g.productId)) {
    ElMessage.warning('请为每个商品选择具体商品')
    return
  }
  if (form.endTime && form.startTime
      && new Date(form.endTime) <= new Date(form.startTime)) {
    ElMessage.warning('结束时间必须晚于开始时间')
    return
  }

  submitting.value = true
  try {
    const payload = { ...form }
    if (form.id) {
      await updateActivity(form.id, payload)
      ElMessage.success('活动已更新')
    } else {
      await publishActivity(payload)
      ElMessage.success('活动发布成功，库存已预热到 Redis')
    }
    dialogVisible.value = false
    loadList()
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

async function openDetail(row) {
  try {
    const res = await getActivityDetail(row.id)
    detail.value = res.data
    detailVisible.value = true
  } catch (e) { /* 拦截器已提示 */ }
}

async function onOnline(row) {
  try {
    await changeActivityStatus(row.id, 1)
    ElMessage.success('活动已上线，Redis 库存已预热')
    loadList()
  } catch (e) { /* 拦截器已提示 */ }
}

async function onOffline(row) {
  try {
    await ElMessageBox.confirm(
      `下线后用户将无法抢购「${row.name}」，确定吗？`, '下线确认',
      { type: 'warning' })
  } catch { return }
  try {
    await changeActivityStatus(row.id, 2)
    ElMessage.success('活动已下线')
    loadList()
  } catch (e) { /* 拦截器已提示 */ }
}

async function onResetStock(row) {
  try {
    await ElMessageBox.confirm(
      `将「${row.name}」的库存恢复为总量，已售与锁定记录会被清空。确定吗？`,
      '重置库存', { type: 'warning' })
  } catch { return }
  try {
    await resetActivityStock(row.id)
    ElMessage.success('库存已重置')
  } catch (e) { /* 拦截器已提示 */ }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除活动「${row.name}」吗？已有订单的活动无法删除。`,
      '删除确认', { type: 'warning' })
  } catch { return }
  try {
    await deleteActivity(row.id)
    ElMessage.success('活动已删除')
    loadList()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(() => {
  loadList()
  // 复用商品分类接口拿到在售商品作为候选项
  getCategories().then(res => { products.value = res.data || [] })
})
</script>

<style scoped>
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-head h2 { margin: 0; font-size: 18px; }
.mb12 { margin-bottom: 12px; }

.filter-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.activity-cell { display: flex; gap: 10px; align-items: center; }
.cover {
  width: 44px; height: 44px; border-radius: 6px;
  object-fit: cover; background: #f2f3f5; flex-shrink: 0;
}
.cover.placeholder {
  display: flex; align-items: center; justify-content: center;
  color: #fff; font-size: 12px;
  background: linear-gradient(120deg, #e4393c, #8b1a1c);
}
.activity-cell .name { font-size: 13px; font-weight: 500; margin-bottom: 2px; }
.activity-cell .sub { font-size: 12px; color: var(--ec-text-light); }
.sub { font-size: 12px; color: var(--ec-text-light); }

.goods-editor { width: 100%; }
.goods-row {
  display: flex; gap: 8px; align-items: center; margin-bottom: 8px;
}
.tip {
  font-size: 12px; color: var(--ec-text-light);
  line-height: 1.6; margin-top: 4px;
}

.detail-title {
  font-size: 14px; margin: 16px 0 10px;
  padding-left: 8px; border-left: 3px solid var(--ec-primary);
  font-weight: 500;
}

.stock-summary {
  display: flex; gap: 28px; padding: 14px;
  background: #fafafa; border-radius: 8px;
}
.sum-item { display: flex; flex-direction: column; gap: 2px; }
.sum-item .num { font-size: 20px; font-weight: 600; }
.sum-item .num.total { color: var(--ec-primary); }
.sum-item .txt { font-size: 12px; color: var(--ec-text-light); }
</style>
