<template>
  <div class="merchant-product">
    <div class="page-head">
      <h2>商品管理</h2>
      <el-button type="danger" @click="openDialog()">
        <el-icon><Plus /></el-icon>发布商品
      </el-button>
    </div>

    <!-- 筛选 -->
    <div class="filter-bar">
      <el-input v-model="query.keyword" placeholder="搜索商品名称" clearable
                style="width: 220px" @keyup.enter="loadList" />
      <el-select v-model="query.auditStatus" placeholder="审核状态" clearable
                 style="width: 130px" @change="loadList">
        <el-option label="待审核" :value="0" />
        <el-option label="审核通过" :value="1" />
        <el-option label="审核拒绝" :value="2" />
      </el-select>
      <el-select v-model="query.status" placeholder="上架状态" clearable
                 style="width: 120px" @change="loadList">
        <el-option label="已上架" :value="1" />
        <el-option label="已下架" :value="0" />
      </el-select>
      <el-button @click="loadList">查询</el-button>
      <el-button link @click="resetQuery">重置</el-button>
    </div>

    <!-- 列表 -->
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
      <el-table-column label="价格" width="110">
        <template #default="{ row }">
          <span class="price">¥{{ row.price }}</span>
        </template>
      </el-table-column>
      <el-table-column prop="stock" label="库存" width="80" />
      <el-table-column prop="sales" label="销量" width="80" />
      <el-table-column label="审核状态" width="100">
        <template #default="{ row }">
          <el-tag :type="auditTagType(row.auditStatus)" size="small">
            {{ auditText(row.auditStatus) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="上架状态" width="90">
        <template #default="{ row }">
          <el-switch :model-value="row.status === 1" :disabled="!canShelf(row)"
                     @change="v => onToggleShelf(row, v)" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="openSkuDialog(row)">SKU</el-button>
          <el-button link type="primary" @click="openDialog(row)">编辑</el-button>
          <el-button link type="danger" @click="onDelete(row)">删除</el-button>
        </template>
      </el-table-column>
      <template #empty>
        <el-empty description="还没有商品，点击右上角发布第一个商品" :image-size="80" />
      </template>
    </el-table>

    <!-- 发布/编辑商品 -->
    <el-dialog v-model="dialogVisible" :title="form.id ? '编辑商品' : '发布商品'"
               width="720px" :close-on-click-modal="false" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="商品名称" prop="name">
          <el-input v-model="form.name" maxlength="100" show-word-limit />
        </el-form-item>
        <el-form-item label="商品分类" prop="categoryId">
          <el-select v-model="form.categoryId" placeholder="选择分类" style="width: 100%">
            <el-option v-for="c in categories" :key="c.id" :label="c.name" :value="c.id" />
          </el-select>
        </el-form-item>
        <el-form-item label="副标题">
          <el-input v-model="form.subtitle" maxlength="100" placeholder="一句话卖点" />
        </el-form-item>
        <el-form-item label="主图地址">
          <el-input v-model="form.mainImage" placeholder="图片 URL" />
        </el-form-item>
        <el-form-item label="商品详情">
          <el-input v-model="form.description" type="textarea" :rows="3" maxlength="1000" />
        </el-form-item>
        <el-row :gutter="12">
          <el-col :span="12">
            <el-form-item label="售价" prop="price">
              <el-input-number v-model="form.price" :min="0.01" :precision="2" :step="1"
                               style="width: 100%" />
            </el-form-item>
          </el-col>
          <el-col :span="12">
            <el-form-item label="划线价">
              <el-input-number v-model="form.originPrice" :min="0" :precision="2"
                               :step="1" style="width: 100%" />
            </el-form-item>
          </el-col>
        </el-row>

        <el-divider content-position="left">商品规格</el-divider>
        <div class="spec-editor">
          <div v-for="(group, gi) in form.specs" :key="gi" class="spec-group">
            <div class="spec-head">
              <el-input v-model="group.name" placeholder="规格名，如：颜色" style="width: 140px" />
              <el-button link type="danger" @click="removeSpec(gi)">
                <el-icon><Delete /></el-icon>
              </el-button>
            </div>
            <div class="spec-values">
              <el-tag v-for="(v, vi) in group.values" :key="vi" closable
                      @close="removeSpecValue(gi, vi)">
                {{ v }}
              </el-tag>
              <el-input v-if="addingValue[gi]" v-model="newValue[gi]" size="small"
                        style="width: 100px" @keyup.enter="addSpecValue(gi)" />
              <el-button v-else size="small" @click="startAddValue(gi)">
                <el-icon><Plus /></el-icon>
              </el-button>
            </div>
          </div>
          <el-button size="small" @click="addSpec">
            <el-icon><Plus /></el-icon>添加规格组
          </el-button>
        </div>

        <el-form-item label="SKU" style="margin-top: 16px">
          <el-table :data="form.skus" size="small" border>
            <el-table-column label="规格组合" min-width="140">
              <template #default="{ row }">
                <el-input v-model="row.specText" size="small" placeholder="默认" />
              </template>
            </el-table-column>
            <el-table-column label="价格" width="130">
              <template #default="{ row }">
                <el-input-number v-model="row.price" :min="0.01" :precision="2"
                                 size="small" controls-position="right" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="库存" width="110">
              <template #default="{ row }">
                <el-input-number v-model="row.stock" :min="0" size="small"
                                 controls-position="right" style="width: 100%" />
              </template>
            </el-table-column>
            <el-table-column label="编码" width="110">
              <template #default="{ row }">
                <el-input v-model="row.skuCode" size="small" />
              </template>
            </el-table-column>
            <el-table-column label="操作" width="60">
              <template #default="{ $index }">
                <el-button link type="danger" @click="form.skus.splice($index, 1)">
                  删除
                </el-button>
              </template>
            </el-table-column>
          </el-table>
          <div class="sku-hint">
            <el-button link type="primary" @click="addSku">
              <el-icon><Plus /></el-icon>添加 SKU
            </el-button>
            <span class="muted">
              合计库存：{{ totalSkuStock }} 件（保存后自动同步到商品库存）
            </span>
          </div>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="onSubmit">
          {{ form.id ? '保存并重新提交审核' : '提交审核' }}
        </el-button>
      </template>
    </el-dialog>

    <!-- SKU 快捷编辑 -->
    <el-dialog v-model="skuDialogVisible" title="SKU 价格与库存" width="640px">
      <el-table :data="skuList" size="small" border>
        <el-table-column prop="specText" label="规格" width="130" />
        <el-table-column label="价格" width="140">
          <template #default="{ row }">
            <el-input-number v-model="row.price" :min="0.01" :precision="2" size="small"
                             controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="库存" width="120">
          <template #default="{ row }">
            <el-input-number v-model="row.stock" :min="0" size="small"
                             controls-position="right" style="width: 100%" />
          </template>
        </el-table-column>
        <el-table-column label="编码" min-width="110">
          <template #default="{ row }">
            <el-input v-model="row.skuCode" size="small" />
          </template>
        </el-table-column>
        <el-table-column label="启用" width="70">
          <template #default="{ row }">
            <el-switch v-model="row.enabled" />
          </template>
        </el-table-column>
      </el-table>
      <template #footer>
        <el-button @click="skuDialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="savingSku" @click="saveSkus">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus, Delete } from '@element-plus/icons-vue'
import {
  listMyProducts, createMerchantProduct, updateMerchantProduct,
  deleteMerchantProduct, changeProductStatus, getProductSkus, updateSku
} from '@/api/merchant'
import { getCategories } from '@/api'

const loading = ref(false)
const submitting = ref(false)
const savingSku = ref(false)
const dialogVisible = ref(false)
const skuDialogVisible = ref(false)
const formRef = ref()

const list = ref([])
const categories = ref([])
const skuList = ref([])
const currentProductId = ref(null)
const addingValue = ref({})
const newValue = ref({})

const query = reactive({ keyword: '', auditStatus: null, status: null })

const emptyForm = () => ({
  id: null, categoryId: null, name: '', subtitle: '', description: '',
  mainImage: '', price: 1, originPrice: null, stock: 0,
  specs: [], skus: [{ specText: '默认', price: 1, stock: 0, skuCode: '', status: 1 }]
})
const form = reactive(emptyForm())

const rules = {
  name: [{ required: true, message: '请输入商品名称', trigger: 'blur' }],
  categoryId: [{ required: true, message: '请选择分类', trigger: 'change' }],
  price: [{ required: true, message: '请输入售价', trigger: 'blur' }]
}

const totalSkuStock = computed(() =>
  form.skus.reduce((sum, s) => sum + (Number(s.stock) || 0), 0))

function auditText(s) {
  return { 0: '待审核', 1: '已通过', 2: '已拒绝' }[s] || '未知'
}
function auditTagType(s) {
  return { 0: 'warning', 1: 'success', 2: 'danger' }[s] || 'info'
}
/** 未过审的商品不能上架 */
function canShelf(row) {
  return row.auditStatus === 1
}

async function loadList() {
  loading.value = true
  try {
    const res = await listMyProducts(query)
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

function resetQuery() {
  query.keyword = ''
  query.auditStatus = null
  query.status = null
  loadList()
}

function openDialog(row) {
  Object.assign(form, emptyForm())
  if (row) {
    // 编辑时把已有数据回填；规格与 SKU 异步拉取
    Object.assign(form, {
      id: row.id, categoryId: row.categoryId, name: row.name,
      subtitle: row.subtitle, description: row.description,
      mainImage: row.mainImage, price: Number(row.price),
      originPrice: row.originPrice ? Number(row.originPrice) : null,
      stock: row.stock
    })
    getProductSkus(row.id).then(res => {
      form.skus = (res.data || []).map(s => ({
        specText: s.specText, price: Number(s.price),
        stock: s.stock, skuCode: s.skuCode, status: s.status
      }))
    })
  }
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

function addSpec() {
  form.specs.push({ name: '', values: [] })
}
function removeSpec(gi) {
  form.specs.splice(gi, 1)
}
function startAddValue(gi) {
  addingValue.value[gi] = true
  newValue.value[gi] = ''
}
function addSpecValue(gi) {
  const v = (newValue.value[gi] || '').trim()
  if (v && !form.specs[gi].values.includes(v)) {
    form.specs[gi].values.push(v)
  }
  addingValue.value[gi] = false
  newValue.value[gi] = ''
}
function removeSpecValue(gi, vi) {
  form.specs[gi].values.splice(vi, 1)
}
function addSku() {
  form.skus.push({ specText: '', price: form.price || 1, stock: 0, skuCode: '', status: 1 })
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  if (!form.skus.length) {
    ElMessage.warning('至少需要一个 SKU')
    return
  }
  // SKU 库存汇总到商品库存，保证两个口径一致
  form.stock = totalSkuStock.value

  submitting.value = true
  try {
    const payload = {
      categoryId: form.categoryId, name: form.name, subtitle: form.subtitle,
      description: form.description, mainImage: form.mainImage,
      price: form.price, originPrice: form.originPrice, stock: form.stock,
      specs: form.specs.filter(s => s.name && s.values.length),
      skus: form.skus
    }
    if (form.id) {
      await updateMerchantProduct(form.id, payload)
    } else {
      await createMerchantProduct(payload)
    }
    ElMessage.success('已提交，等待平台审核')
    dialogVisible.value = false
    loadList()
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

async function onToggleShelf(row, val) {
  try {
    await changeProductStatus(row.id, val ? 1 : 0)
    row.status = val ? 1 : 0
    ElMessage.success(val ? '商品已上架' : '商品已下架')
  } catch (e) {
    // 失败时回滚开关状态
    row.status = val ? 0 : 1
  }
}

async function onDelete(row) {
  try {
    await ElMessageBox.confirm(
      `确定要删除「${row.name}」吗？有销量的商品只能下架不能删除。`,
      '提示', { type: 'warning' })
  } catch { return }
  try {
    await deleteMerchantProduct(row.id)
    ElMessage.success('商品已删除')
    loadList()
  } catch (e) { /* 拦截器已提示 */ }
}

async function openSkuDialog(row) {
  currentProductId.value = row.id
  try {
    const res = await getProductSkus(row.id)
    skuList.value = (res.data || []).map(s => ({
      id: s.id, specText: s.specText, price: Number(s.price),
      stock: s.stock, skuCode: s.skuCode, enabled: s.status === 1
    }))
    skuDialogVisible.value = true
  } catch (e) { /* 拦截器已提示 */ }
}

async function saveSkus() {
  savingSku.value = true
  try {
    // 逐条保存：每个 SKU 是独立的价格库存变更，批量接口反而不好做部分失败处理
    for (const sku of skuList.value) {
      await updateSku(currentProductId.value, sku.id, {
        price: sku.price,
        stock: sku.stock,
        skuCode: sku.skuCode,
        status: sku.enabled ? 1 : 0
      })
    }
    ElMessage.success('SKU 已保存')
    skuDialogVisible.value = false
    loadList()
  } catch (e) { /* 拦截器已提示 */ } finally {
    savingSku.value = false
  }
}

onMounted(() => {
  loadList()
  getCategories().then(res => (categories.value = res.data || []))
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

.filter-bar {
  display: flex;
  gap: 10px;
  margin-bottom: 14px;
  flex-wrap: wrap;
}

.goods-cell { display: flex; gap: 10px; align-items: center; }
.thumb {
  width: 44px; height: 44px; border-radius: 6px;
  object-fit: cover; background: #f2f3f5; flex-shrink: 0;
}
.goods-cell .name {
  font-size: 13px; line-height: 1.4; margin-bottom: 2px;
  display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical;
  overflow: hidden;
}
.goods-cell .sub { font-size: 12px; color: var(--ec-text-light); }

.spec-editor {
  border: 1px solid var(--ec-border);
  border-radius: 8px;
  padding: 12px;
}
.spec-group {
  padding: 10px 0;
  border-bottom: 1px dashed var(--ec-border);
}
.spec-group:last-of-type { border-bottom: none; }
.spec-head {
  display: flex; align-items: center; gap: 8px; margin-bottom: 8px;
}
.spec-values {
  display: flex; gap: 6px; flex-wrap: wrap; align-items: center;
}

.sku-hint {
  display: flex; align-items: center; gap: 12px; margin-top: 8px;
}
.sku-hint .muted { font-size: 12px; color: var(--ec-text-light); }
</style>
