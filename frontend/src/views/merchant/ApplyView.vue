<template>
  <div class="apply-view">
    <div class="page-head"><h2>商家入驻</h2></div>

    <!-- 已提交申请：显示状态 -->
    <div v-if="myApply" class="card-box">
      <el-descriptions :column="1" border>
        <el-descriptions-item label="店铺名称">{{ myApply.shopName }}</el-descriptions-item>
        <el-descriptions-item label="联系人">
          {{ myApply.contactName }} {{ myApply.contactPhone }}
        </el-descriptions-item>
        <el-descriptions-item label="类目">
          {{ myApply.businessType === 2 ? '企业' : '个人' }}
        </el-descriptions-item>
        <el-descriptions-item label="提交时间">{{ myApply.createTime }}</el-descriptions-item>
        <el-descriptions-item label="审核状态">
          <el-tag :type="{ 0: 'warning', 1: 'success', 2: 'danger', 3: 'info' }[myApply.status]">
            {{ { 0: '待审核', 1: '已通过', 2: '已拒绝', 3: '已撤销' }[myApply.status] }}
          </el-tag>
        </el-descriptions-item>
        <el-descriptions-item v-if="myApply.auditRemark" label="审核意见">
          {{ myApply.auditRemark }}
        </el-descriptions-item>
      </el-descriptions>

      <div class="actions">
        <el-button v-if="myApply.status === 0" type="danger" plain @click="onRevoke">
          撤销申请
        </el-button>
        <el-button v-if="myApply.status === 1" type="danger" @click="$router.push('/merchant')">
          进入商家中心
        </el-button>
        <el-button v-if="myApply.status === 2" @click="resetForm">重新申请</el-button>
      </div>
    </div>

    <!-- 申请表 -->
    <el-form v-else ref="formRef" :model="form" :rules="rules" label-width="100px"
             style="max-width: 560px" class="card-box">
      <el-form-item label="店铺名称" prop="shopName">
        <el-input v-model="form.shopName" maxlength="50" placeholder="2-30 个字符" />
      </el-form-item>
      <el-form-item label="店铺简介">
        <el-input v-model="form.shopDesc" type="textarea" :rows="3" maxlength="500"
                  show-word-limit placeholder="介绍一下你的店铺" />
      </el-form-item>
      <el-form-item label="联系人" prop="contactName">
        <el-input v-model="form.contactName" maxlength="50" />
      </el-form-item>
      <el-form-item label="联系电话" prop="contactPhone">
        <el-input v-model="form.contactPhone" maxlength="11" placeholder="11 位手机号" />
      </el-form-item>
      <el-form-item label="经营类目">
        <el-radio-group v-model="form.businessType">
          <el-radio :value="1">个人</el-radio>
          <el-radio :value="2">企业</el-radio>
        </el-radio-group>
      </el-form-item>
      <el-form-item v-if="form.businessType === 2" label="营业执照号" prop="licenseNo">
        <el-input v-model="form.licenseNo" maxlength="50" placeholder="企业入驻必填" />
      </el-form-item>
      <el-form-item>
        <el-button type="danger" :loading="submitting" @click="onSubmit">提交申请</el-button>
        <span class="tip">提交后平台会在 1-3 个工作日内审核</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { applyMerchant, getMyApply, revokeApply } from '@/api/merchant'

const router = useRouter()
const formRef = ref()
const submitting = ref(false)
const myApply = ref(null)

const emptyForm = () => ({
  shopName: '', shopDesc: '', contactName: '', contactPhone: '',
  businessType: 1, licenseNo: '', idCard: ''
})
const form = reactive(emptyForm())

const rules = {
  shopName: [
    { required: true, message: '请输入店铺名称', trigger: 'blur' },
    { min: 2, max: 30, message: '店铺名称 2-30 个字符', trigger: 'blur' }
  ],
  contactName: [{ required: true, message: '请输入联系人', trigger: 'blur' }],
  contactPhone: [
    { required: true, message: '请输入联系电话', trigger: 'blur' },
    { pattern: /^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }
  ],
  licenseNo: [{
    validator: (rule, value, callback) => {
      if (form.businessType === 2 && !value) {
        callback(new Error('企业入驻必须填写营业执照号'))
      } else {
        callback()
      }
    },
    trigger: 'blur'
  }]
}

async function load() {
  try {
    const res = await getMyApply()
    myApply.value = res.data || null
  } catch (e) { /* 忽略 */ }
}

function resetForm() {
  Object.assign(form, emptyForm())
  myApply.value = null
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return
  submitting.value = true
  try {
    await applyMerchant({ ...form })
    ElMessage.success('申请已提交，请等待审核')
    load()
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

async function onRevoke() {
  try {
    await ElMessageBox.confirm('撤销后可重新提交，确定撤销吗？', '提示', { type: 'warning' })
  } catch { return }
  try {
    await revokeApply(myApply.value.id)
    ElMessage.success('申请已撤销')
    resetForm()
  } catch (e) { /* 拦截器已提示 */ }
}

onMounted(load)
</script>

<style scoped>
.page-head { margin-bottom: 16px; }
.page-head h2 { margin: 0; font-size: 18px; }
.card-box {
  background: #fafafa;
  border-radius: 8px;
  padding: 20px;
}
.actions {
  display: flex; gap: 12px; margin-top: 16px;
}
.tip { margin-left: 12px; font-size: 12px; color: var(--ec-text-light); }
</style>
