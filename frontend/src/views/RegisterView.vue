<template>
  <div class="register-page">
    <div class="register-card">
      <div class="brand">
        <div class="brand-mark">优选</div>
        <h1>创建账号</h1>
        <p>注册后即可享受完整的购物体验</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="用户名（字母开头，3-20 位）" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="nickname">
          <el-input v-model="form.nickname" placeholder="昵称" :prefix-icon="Postcard" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="密码（6-30 位）"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-form-item prop="confirmPassword">
          <el-input
            v-model="form.confirmPassword"
            type="password"
            placeholder="确认密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-form-item prop="phone">
          <el-input v-model="form.phone" placeholder="手机号（选填）" :prefix-icon="Iphone" />
        </el-form-item>
        <el-form-item prop="email">
          <el-input v-model="form.email" placeholder="邮箱（选填）" :prefix-icon="Message" />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" class="submit" :loading="loading" @click="onSubmit">
            注 册
          </el-button>
        </el-form-item>
      </el-form>

      <div class="tips">
        已有账号？<router-link to="/login">直接登录</router-link>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Postcard, Iphone, Message } from '@element-plus/icons-vue'
import { register } from '@/api'

const router = useRouter()
const formRef = ref()
const loading = ref(false)
const form = reactive({
  username: '',
  nickname: '',
  password: '',
  confirmPassword: '',
  phone: '',
  email: ''
})

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    {
      pattern: /^[a-zA-Z][a-zA-Z0-9_]{2,19}$/,
      message: '需以字母开头，由字母、数字、下划线组成，长度 3-20 位',
      trigger: 'blur'
    }
  ],
  nickname: [{ required: true, message: '请输入昵称', trigger: 'blur' }],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 30, message: '密码长度为 6-30 位', trigger: 'blur' }
  ],
  confirmPassword: [
    { required: true, message: '请再次输入密码', trigger: 'blur' },
    {
      // 自定义校验：两次输入必须一致
      validator: (rule, value, callback) => {
        if (value !== form.password) {
          callback(new Error('两次输入的密码不一致'))
        } else {
          callback()
        }
      },
      trigger: 'blur'
    }
  ],
  phone: [{ pattern: /^$|^1[3-9]\d{9}$/, message: '手机号格式不正确', trigger: 'blur' }],
  email: [{ type: 'email', message: '邮箱格式不正确', trigger: 'blur' }]
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const { confirmPassword, ...payload } = form
    await register(payload)
    ElMessage.success('注册成功，请登录')
    router.push('/login')
  } catch (e) {
    // 提示已由拦截器处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.register-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #fdf0f0 0%, #f5f6f7 60%);
  padding: 20px;
}
.register-card {
  width: 100%;
  max-width: 440px;
  background: #fff;
  border-radius: 12px;
  padding: 32px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}
.brand {
  text-align: center;
  margin-bottom: 20px;
}
.brand-mark {
  display: inline-block;
  background: var(--ec-primary);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  padding: 4px 14px;
  border-radius: 8px;
  margin-bottom: 12px;
}
.brand h1 {
  margin: 0 0 6px;
  font-size: 22px;
}
.brand p {
  margin: 0;
  color: var(--ec-text-light);
  font-size: 13px;
}
.submit {
  width: 100%;
  font-weight: 600;
  letter-spacing: 4px;
}
.tips {
  text-align: center;
  font-size: 13px;
  color: var(--ec-text-light);
}
.tips a {
  color: var(--ec-primary);
  margin-left: 4px;
}
</style>
