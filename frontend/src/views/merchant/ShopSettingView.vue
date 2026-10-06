<template>
  <div v-loading="loading" class="shop-setting">
    <div class="page-head"><h2>店铺设置</h2></div>

    <el-alert v-if="shop.status === 2" type="error" show-icon :closable="false"
              title="店铺已被平台冻结" class="mb12"
              description="冻结期间无法发布商品、修改库存与发货，请联系平台管理员" />

    <el-form label-width="100px" style="max-width: 560px">
      <el-form-item label="店铺名称">
        <el-input v-model="form.shopName" maxlength="50" show-word-limit :disabled="frozen" />
      </el-form-item>
      <el-form-item label="店铺 Logo">
        <el-input v-model="form.shopLogo" placeholder="图片 URL" :disabled="frozen" />
      </el-form-item>
      <el-form-item label="店铺简介">
        <el-input v-model="form.shopDesc" type="textarea" :rows="4" maxlength="500"
                  show-word-limit :disabled="frozen" />
      </el-form-item>

      <el-divider content-position="left">店铺信息（只读）</el-divider>
      <el-descriptions :column="1" border size="small">
        <el-descriptions-item label="商家 ID">{{ shop.id }}</el-descriptions-item>
        <el-descriptions-item label="联系人">{{ shop.contactName }}</el-descriptions-item>
        <el-descriptions-item label="联系电话">{{ shop.contactPhone }}</el-descriptions-item>
        <el-descriptions-item label="经营类目">
          {{ shop.businessType === 2 ? '企业' : '个人' }}
        </el-descriptions-item>
        <el-descriptions-item label="营业执照">
          {{ shop.licenseNo || '个人入驻无需营业执照' }}
        </el-descriptions-item>
        <el-descriptions-item label="店铺状态">
          <el-tag :type="shop.status === 1 ? 'success' : 'danger'" size="small">
            {{ { 1: '正常营业', 2: '已冻结', 3: '已注销' }[shop.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item label="入驻时间">{{ shop.createTime || '-' }}</el-descriptions-item>
        <el-descriptions-item label="累计销售额">¥{{ shop.totalSales || 0 }}</el-descriptions-item>
      </el-descriptions>

      <div class="actions">
        <el-button type="danger" :loading="saving" :disabled="frozen" @click="onSave">
          保存修改
        </el-button>
        <span class="tip">联系人与营业执照属于入驻资质，如需变更请联系平台</span>
      </div>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getMyShop, updateMyShop } from '@/api/merchant'

const loading = ref(false)
const saving = ref(false)
const shop = ref({})
const form = reactive({ shopName: '', shopLogo: '', shopDesc: '' })

const frozen = computed(() => shop.value.status === 2)

async function load() {
  loading.value = true
  try {
    const res = await getMyShop()
    shop.value = res.data || {}
    Object.assign(form, {
      shopName: shop.value.shopName || '',
      shopLogo: shop.value.shopLogo || '',
      shopDesc: shop.value.shopDesc || ''
    })
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

async function onSave() {
  if (!form.shopName.trim()) { ElMessage.warning('店铺名称不能为空'); return }
  saving.value = true
  try {
    await updateMyShop({ ...form })
    ElMessage.success('店铺信息已更新')
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    saving.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 18px; }
.mb12 { margin-bottom: 12px; }
.actions {
  display: flex; align-items: center; gap: 12px; margin-top: 16px;
}
.tip { font-size: 12px; color: var(--ec-text-light); }
</style>
