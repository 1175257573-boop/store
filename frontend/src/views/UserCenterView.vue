<template>
  <div class="user-center container">
    <h2 class="page-title">个人中心</h2>

    <div class="layout">
      <!-- 左侧资料卡 -->
      <div class="card-box profile">
        <div class="avatar">
          <el-avatar :size="72" :src="userStore.userInfo?.avatar">
            {{ (userStore.nickname || '?').charAt(0) }}
          </el-avatar>
        </div>
        <div class="name">{{ userStore.nickname }}</div>
        <div class="username">@{{ userStore.userInfo?.username }}</div>

        <el-divider />

        <div class="stats">
          <div class="stat" @click="go('/orders?status=0')">
            <b>{{ counts.status0 || 0 }}</b><span>待付款</span>
          </div>
          <div class="stat" @click="go('/orders?status=2')">
            <b>{{ counts.status2 || 0 }}</b><span>待收货</span>
          </div>
          <div class="stat" @click="go('/orders?status=3')">
            <b>{{ counts.status3 || 0 }}</b><span>已完成</span>
          </div>
        </div>

        <el-button type="danger" plain class="logout-btn" @click="onLogout">
          退出登录
        </el-button>
      </div>

      <!-- 右侧功能区 -->
      <div class="right">
        <el-tabs v-model="activeTab">
          <!-- 资料编辑 -->
          <el-tab-pane label="个人资料" name="profile">
            <el-form :model="profile" label-width="90px" class="form-box">
              <el-form-item label="用户名">
                <el-input :model-value="userStore.userInfo?.username" disabled />
                <div class="tip">用户名注册后不可修改</div>
              </el-form-item>
              <el-form-item label="昵称">
                <el-input v-model="profile.nickname" maxlength="20" />
              </el-form-item>
              <el-form-item label="手机号">
                <el-input v-model="profile.phone" maxlength="11" />
              </el-form-item>
              <el-form-item label="邮箱">
                <el-input v-model="profile.email" />
              </el-form-item>
              <el-form-item label="性别">
                <el-radio-group v-model="profile.gender">
                  <el-radio :value="0">保密</el-radio>
                  <el-radio :value="1">男</el-radio>
                  <el-radio :value="2">女</el-radio>
                </el-radio-group>
              </el-form-item>
              <el-form-item label="生日">
                <el-date-picker
                  v-model="profile.birthday"
                  type="date"
                  value-format="YYYY-MM-DD"
                  placeholder="选择生日"
                />
              </el-form-item>
              <el-form-item>
                <el-button type="danger" :loading="savingProfile" @click="onSaveProfile">
                  保存修改
                </el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- 修改密码 -->
          <el-tab-pane label="修改密码" name="password">
            <el-form :model="pwd" label-width="90px" class="form-box">
              <el-form-item label="原密码">
                <el-input v-model="pwd.oldPassword" type="password" show-password />
              </el-form-item>
              <el-form-item label="新密码">
                <el-input v-model="pwd.newPassword" type="password" show-password />
              </el-form-item>
              <el-form-item label="确认密码">
                <el-input v-model="pwd.confirmPassword" type="password" show-password />
              </el-form-item>
              <el-form-item>
                <el-button type="danger" :loading="savingPwd" @click="onChangePwd">
                  确认修改
                </el-button>
              </el-form-item>
            </el-form>
          </el-tab-pane>

          <!-- 订单入口 -->
          <el-tab-pane label="我的订单" name="orders">
            <div class="order-entries">
              <div
                v-for="e in orderEntries"
                :key="e.status"
                class="entry"
                @click="go(`/orders?status=${e.status}`)"
              >
                <el-badge :value="counts['status' + e.status] || 0" :hidden="!counts['status' + e.status]">
                  <div class="entry-icon">{{ e.icon }}</div>
                </el-badge>
                <span>{{ e.label }}</span>
              </div>
            </div>
          </el-tab-pane>
        </el-tabs>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useUserStore } from '@/stores/user'
import { getOrderCount, updateProfile, changePassword, getProfile } from '@/api'

const router = useRouter()
const userStore = useUserStore()

const activeTab = ref('profile')
const savingProfile = ref(false)
const savingPwd = ref(false)
const counts = ref({})

const profile = reactive({
  nickname: '',
  phone: '',
  email: '',
  gender: 0,
  birthday: null
})
const pwd = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })

const orderEntries = [
  { status: 0, label: '待付款', icon: '💰' },
  { status: 1, label: '待发货', icon: '📦' },
  { status: 2, label: '待收货', icon: '🚚' },
  { status: 3, label: '已完成', icon: '✅' },
  { status: 4, label: '已取消', icon: '❌' }
]

async function loadData() {
  try {
    const [pRes, cRes] = await Promise.all([getProfile(), getOrderCount()])
    const p = pRes.data
    profile.nickname = p.nickname || ''
    profile.phone = p.phone || ''
    profile.email = p.email || ''
    profile.gender = p.gender ?? 0
    profile.birthday = p.birthday || null
    counts.value = cRes.data || {}
  } catch (e) {
    // 拦截器已处理
  }
}

async function onSaveProfile() {
  if (!profile.nickname) {
    ElMessage.warning('昵称不能为空')
    return
  }
  savingProfile.value = true
  try {
    await updateProfile({ ...profile })
    ElMessage.success('资料已更新')
    // 同步本地展示的昵称
    userStore.setProfile({ nickname: profile.nickname })
  } catch (e) {
    // 提示已处理
  } finally {
    savingProfile.value = false
  }
}

async function onChangePwd() {
  if (!pwd.oldPassword || !pwd.newPassword) {
    ElMessage.warning('请填写完整')
    return
  }
  if (pwd.newPassword !== pwd.confirmPassword) {
    ElMessage.error('两次输入的新密码不一致')
    return
  }
  if (pwd.newPassword.length < 6) {
    ElMessage.error('新密码至少 6 位')
    return
  }
  savingPwd.value = true
  try {
    await changePassword({ oldPassword: pwd.oldPassword, newPassword: pwd.newPassword })
    ElMessage.success('密码已修改')
    pwd.oldPassword = pwd.newPassword = pwd.confirmPassword = ''
  } catch (e) {
    // 提示已处理
  } finally {
    savingPwd.value = false
  }
}

async function onLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
  } catch {
    return
  }
  userStore.logout()
  ElMessage.success('已退出登录')
  router.push('/login')
}

function go(path) {
  router.push(path)
}

onMounted(loadData)
</script>

<style scoped>
.user-center {
  padding-top: 20px;
}
.page-title {
  font-size: 20px;
  margin: 0 0 16px;
}
.layout {
  display: grid;
  grid-template-columns: 280px 1fr;
  gap: 16px;
  align-items: start;
}
@media (max-width: 860px) {
  .layout {
    grid-template-columns: 1fr;
  }
}

.profile {
  text-align: center;
}
.avatar {
  margin-bottom: 12px;
}
.name {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.username {
  font-size: 13px;
  color: var(--ec-text-light);
}
.stats {
  display: flex;
  justify-content: space-around;
  margin-bottom: 16px;
}
.stat {
  display: flex;
  flex-direction: column;
  gap: 2px;
  cursor: pointer;
  padding: 6px 4px;
  border-radius: 6px;
}
.stat:hover {
  background: #f7f7f7;
}
.stat b {
  font-size: 18px;
  color: var(--ec-primary);
}
.stat span {
  font-size: 12px;
  color: var(--ec-text-light);
}
.logout-btn {
  width: 100%;
}

.right {
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 8px 20px 20px;
  min-height: 420px;
}
.form-box {
  max-width: 460px;
  padding-top: 12px;
}
.tip {
  font-size: 12px;
  color: var(--ec-text-light);
  line-height: 1.6;
  margin-top: 2px;
}

.order-entries {
  display: flex;
  gap: 30px;
  padding: 24px 10px;
  flex-wrap: wrap;
}
.entry {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 8px;
  cursor: pointer;
  font-size: 13px;
  color: var(--ec-text-light);
}
.entry:hover span {
  color: var(--ec-primary);
}
.entry-icon {
  width: 56px;
  height: 56px;
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 26px;
  background: #f7f7f7;
  border-radius: 50%;
}
</style>
