<template>
  <div class="address-page container">
    <div class="page-head">
      <h2 class="page-title">收货地址</h2>
      <el-button type="danger" @click="openDialog()">
        <el-icon><Plus /></el-icon>新增地址
      </el-button>
    </div>

    <div v-loading="loading">
      <div v-if="addresses.length" class="list">
        <div v-for="a in addresses" :key="a.id" class="card-box item">
          <div class="info">
            <div class="line1">
              <b>{{ a.receiver }}</b>
              <span class="phone">{{ a.phone }}</span>
              <el-tag v-if="a.isDefault === 1" type="danger" size="small">默认地址</el-tag>
            </div>
            <div class="line2">{{ a.province }}{{ a.city }}{{ a.district }}{{ a.detail }}</div>
          </div>
          <div class="actions">
            <el-button v-if="a.isDefault !== 1" link type="primary" @click="onSetDefault(a)">
              设为默认
            </el-button>
            <el-button link @click="openDialog(a)">编辑</el-button>
            <el-button link type="danger" @click="onDelete(a)">删除</el-button>
          </div>
        </div>
      </div>

      <div v-else-if="!loading" class="card-box">
        <el-empty description="还没有收货地址">
          <el-button type="danger" @click="openDialog()">立即添加</el-button>
        </el-empty>
      </div>
    </div>

    <!-- 新增/编辑弹窗 -->
    <el-dialog
      v-model="dialogVisible"
      :title="form.id ? '编辑地址' : '新增地址'"
      width="520px"
      :close-on-click-modal="false"
    >
      <el-form ref="formRef" :model="form" :rules="rules" label-width="90px">
        <el-form-item label="收货人" prop="receiver">
          <el-input v-model="form.receiver" placeholder="请输入收货人姓名" />
        </el-form-item>
        <el-form-item label="手机号" prop="phone">
          <el-input v-model="form.phone" placeholder="请输入 11 位手机号" maxlength="11" />
        </el-form-item>
        <el-form-item label="所在地区" required>
          <el-input v-model="form.province" placeholder="省份" class="half" />
          <el-input v-model="form.city" placeholder="城市" class="half" />
          <el-input v-model="form.district" placeholder="区/县" class="half" style="margin-top: 8px" />
        </el-form-item>
        <el-form-item label="详细地址" prop="detail">
          <el-input
            v-model="form.detail"
            type="textarea"
            :rows="2"
            maxlength="200"
            show-word-limit
            placeholder="街道、楼牌号等"
          />
        </el-form-item>
        <el-form-item label="设为默认">
          <el-switch v-model="form.isDefault" :active-value="1" :inactive-value="0" />
          <span class="switch-tip">设为默认后，下单会自动选中该地址</span>
        </el-form-item>
      </el-form>

      <template #footer>
        <el-button @click="dialogVisible = false">取消</el-button>
        <el-button type="danger" :loading="submitting" @click="onSubmit">保存</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import {
  getAddressList,
  addAddress,
  updateAddress,
  deleteAddress,
  setDefaultAddress
} from '@/api'

const loading = ref(false)
const submitting = ref(false)
const dialogVisible = ref(false)
const addresses = ref([])
const formRef = ref()

const emptyForm = () => ({
  id: null,
  receiver: '',
  phone: '',
  province: '',
  city: '',
  district: '',
  detail: '',
  isDefault: 0
})
const form = reactive(emptyForm())

const rules = {
  receiver: [{ required: true, message: '请输入收货人', trigger: 'blur' }],
  phone: [
    { required: true, message: '请输入手机号', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  detail: [{ required: true, message: '请输入详细地址', trigger: 'blur' }],
  province: [{ required: true, message: '请输入省份', trigger: 'blur' }],
  city: [{ required: true, message: '请输入城市', trigger: 'blur' }],
  district: [{ required: true, message: '请输入区县', trigger: 'blur' }]
}

async function loadList() {
  loading.value = true
  try {
    const res = await getAddressList()
    addresses.value = res.data || []
  } catch (e) {
    addresses.value = []
  } finally {
    loading.value = false
  }
}

function openDialog(address) {
  Object.assign(form, address ? { ...address } : emptyForm())
  dialogVisible.value = true
  formRef.value?.clearValidate()
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  submitting.value = true
  try {
    const payload = {
      receiver: form.receiver,
      phone: form.phone,
      province: form.province,
      city: form.city,
      district: form.district,
      detail: form.detail,
      isDefault: form.isDefault
    }
    if (form.id) {
      await updateAddress(form.id, payload)
      ElMessage.success('地址已更新')
    } else {
      await addAddress(payload)
      ElMessage.success('地址已保存')
    }
    dialogVisible.value = false
    loadList()
  } catch (e) {
    // 提示已处理
  } finally {
    submitting.value = false
  }
}

async function onDelete(a) {
  try {
    await ElMessageBox.confirm('确定要删除该收货地址吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  await deleteAddress(a.id)
  ElMessage.success('地址已删除')
  loadList()
}

async function onSetDefault(a) {
  await setDefaultAddress(a.id)
  ElMessage.success('已设为默认地址')
  loadList()
}

onMounted(loadList)
</script>

<style scoped>
.address-page {
  padding-top: 20px;
}
.page-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}
.page-title {
  font-size: 20px;
  margin: 0;
}

.item {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  gap: 20px;
}
.line1 {
  display: flex;
  align-items: center;
  gap: 12px;
  margin-bottom: 6px;
}
.line1 .phone {
  color: var(--ec-text-light);
  font-size: 14px;
}
.line2 {
  font-size: 13px;
  color: var(--ec-text-light);
  line-height: 1.5;
}
.actions {
  display: flex;
  gap: 4px;
  flex-shrink: 0;
}

.half {
  width: calc(50% - 4px);
  display: inline-block;
}
.half:nth-child(2) {
  margin-left: 8px;
}
.switch-tip {
  margin-left: 12px;
  font-size: 12px;
  color: var(--ec-text-light);
}
</style>
