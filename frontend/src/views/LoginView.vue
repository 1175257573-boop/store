<template>
  <div class="login-page">
    <div class="login-card">
      <div class="brand">
        <div class="brand-mark">优选</div>
        <h1>欢迎回来</h1>
        <p>登录后即可加购与下单</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onSubmit">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input
            v-model="form.password"
            type="password"
            placeholder="请输入密码"
            :prefix-icon="Lock"
            show-password
          />
        </el-form-item>
        <el-form-item>
          <el-button type="danger" class="submit" :loading="loading" @click="onSubmit">
            登 录
          </el-button>
        </el-form-item>
      </el-form>

      <div class="tips">
        <span>还没有账号？<router-link to="/register">立即注册</router-link></span>
      </div>

      <el-divider>
        <span class="divider-text">演示账号</span>
      </el-divider>
      <div class="demo-accounts">
        <el-tag
          v-for="acc in demoAccounts"
          :key="acc.username"
          class="demo-tag"
          type="info"
          effect="plain"
          @click="fillDemo(acc)"
        >
          {{ acc.label }}
        </el-tag>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock } from '@element-plus/icons-vue'
import { useUserStore } from '@/stores/user'
import { login } from '@/api'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()

const formRef = ref()
const loading = ref(false)
const form = reactive({ username: '', password: '' })

const rules = {
  username: [
    { required: true, message: '请输入用户名', trigger: 'blur' },
    { min: 3, max: 20, message: '用户名长度为 3-20 位', trigger: 'blur' }
  ],
  password: [
    { required: true, message: '请输入密码', trigger: 'blur' },
    { min: 6, max: 30, message: '密码长度为 6-30 位', trigger: 'blur' }
  ]
}

const demoAccounts = [
  { label: 'demo / 123456', username: 'demo', password: '123456' },
  { label: 'admin / 123456', username: 'admin', password: '123456' }
]

function fillDemo(acc) {
  form.username = acc.username
  form.password = acc.password
}

async function onSubmit() {
  const valid = await formRef.value.validate().catch(() => false)
  if (!valid) return

  loading.value = true
  try {
    const res = await login(form)
    userStore.setLogin(res.data)
    ElMessage.success(`欢迎回来，${res.data.nickname}`)
    // 登录前想去的页面优先，否则回首页
    const redirect = route.query.redirect || '/'
    router.push(redirect)
  } catch (e) {
    // 错误提示已由 axios 拦截器统一处理
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: linear-gradient(135deg, #fdf0f0 0%, #f5f6f7 60%);
  padding: 20px;
}
.login-card {
  width: 100%;
  max-width: 400px;
  background: #fff;
  border-radius: 12px;
  padding: 36px 32px 28px;
  box-shadow: 0 8px 32px rgba(0, 0, 0, 0.08);
}
.brand {
  text-align: center;
  margin-bottom: 24px;
}
.brand-mark {
  display: inline-block;
  background: var(--ec-primary);
  color: #fff;
  font-size: 20px;
  font-weight: 700;
  padding: 4px 14px;
  border-radius: 8px;
  margin-bottom: 14px;
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
.divider-text {
  font-size: 12px;
  color: var(--ec-text-light);
}
.demo-accounts {
  display: flex;
  gap: 10px;
  justify-content: center;
}
.demo-tag {
  cursor: pointer;
}
.demo-tag:hover {
  color: var(--ec-primary);
  border-color: var(--ec-primary);
}
</style>
