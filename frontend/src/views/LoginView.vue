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
        <span class="divider-text">演示账号 · 点击卡片一键登录</span>
      </el-divider>

      <!--
        演示入口的设计要点：
        1. 凭据直接展示 —— 评审者要能「查看」而不只是「试一下」
        2. 权限说明 —— 让人知道这个账号能干什么，省得自己点一遍
        3. 一键登录 —— 卡片整体可点，不必先填再点登录
        4. 店铺数据隔离 —— 商家演示账号只管自己店铺，看不到别家数据
      -->
      <div class="demo-grid">
        <div
          v-for="acc in demoAccounts"
          :key="acc.username"
          class="demo-card"
          :class="{ active: form.username === acc.username }"
          :title="acc.tip"
          @click="quickLogin(acc)"
        >
          <div class="demo-head">
            <el-icon class="demo-icon" :style="{ color: acc.color }">
              <component :is="acc.icon" />
            </el-icon>
            <div class="demo-role">
              <div class="role-name">{{ acc.roleName }}</div>
              <div class="role-desc">{{ acc.roleDesc }}</div>
            </div>
            <el-tag v-if="acc.badge" :type="acc.badgeType || 'warning'"
                    size="small" effect="dark" class="demo-badge">
              {{ acc.badge }}
            </el-tag>
          </div>

          <div class="demo-cred">
            <span class="cred-item">
              <em>账号</em><code>{{ acc.username }}</code>
            </span>
            <span class="cred-item">
              <em>密码</em><code>{{ acc.password }}</code>
            </span>
          </div>

          <div class="demo-actions">
            <el-button size="small" text type="primary"
                       :disabled="demoLoading === acc.username"
                       @click.stop="fillDemo(acc)">
              {{ form.username === acc.username ? '已填入' : '填入表单' }}
            </el-button>
            <el-button size="small" type="primary" plain
                       :loading="demoLoading === acc.username"
                       @click.stop="quickLogin(acc)">
              一键登录
            </el-button>
          </div>
        </div>
      </div>

      <p class="demo-note">
        <el-icon><InfoFilled /></el-icon>
        以上均为演示环境账号，数据彼此隔离。商家账号仅能管理本店商品与订单，
        看不到其他店铺数据。
      </p>
    </div>
  </div>
</template>

<script setup>
import { ref, reactive } from 'vue'
import { useRouter, useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { User, Lock, Shop, Setting, InfoFilled } from '@element-plus/icons-vue'
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

/**
 * 演示账号配置。
 *
 * <p><b>数据来源</b>：账号由后端种子数据创建（scripts/migrate_merchant_split.py
 * 建的两个店铺 + 早期演示账号），前端只读展示，不在这里创建。
 *
 * <p><b>要不要环境变量隔离</b>：不需要，原因有三：
 * <ol>
 *   <li>演示账号只做<b>只读展示 + 登录入口</b>，不涉及任何写操作；
 *       登录后仍受后端既有的数据隔离约束（商家只能看自己 merchant_id 的数据）。</li>
 *   <li>真正的隔离在<b>后端</b>：商家接口全部走
 *       {@code UserContextHolder.requireMerchantId()} 强制按登录态过滤，
 *       前端就算被篡改也拿不到别家数据。</li>
 *   <li>用环境变量区分演示/生产账号反而危险 —— 演示账号密码一旦进配置文件，
 *       很容易被带着上生产。这里写死常量反而更可控：
 *       生产环境靠「不部署演示数据」来隔离，而不是靠配置切换。</li>
 * </ol>
 *
 * <p><b>如果将来真要做环境隔离</b>，正确做法是在后端加开关
 * （如 {@code app.demo-account.enabled}），由后端决定是否启用演示账号，
 * 而不是在前端读环境变量 —— 前端开关可以被绕过。
 */
const demoAccounts = [
  {
    username: 'digital_shop',
    password: 'shop123456',
    roleName: '商家 · 数码优选',
    roleDesc: '管理数码优选旗舰店的商品与订单',
    badge: '推荐',
    badgeType: 'warning',
    icon: 'Shop',
    color: '#e4393c',
    tip: '数码优选旗舰店：手机通讯 / 电脑办公 / 办公文具，约 61 件商品'
  },
  {
    username: 'life_shop',
    password: 'shop123456',
    roleName: '商家 · 生活优选',
    roleDesc: '管理生活优选生活馆的商品与订单',
    icon: 'Shop',
    color: '#67c23a',
    tip: '生活优选生活馆：家电 / 服饰 / 食品 / 图书 / 家居，约 60 件商品'
  },
  {
    username: 'demo',
    password: '123456',
    roleName: '普通买家',
    roleDesc: '浏览、下单、评价、联系商家',
    icon: 'User',
    color: '#409eff',
    tip: '普通买家账号，可体验完整购物流程'
  },
  {
    username: 'admin',
    password: '123456',
    roleName: '平台管理员',
    roleDesc: '审核商家与商品、查看全平台数据',
    icon: 'Setting',
    color: '#909399',
    tip: '管理员账号，可进入管理后台审核'
  }
]

/** 正在一键登录的账号（用于按钮 loading），null 表示无 */
const demoLoading = ref(null)

function fillDemo(acc) {
  form.username = acc.username
  form.password = acc.password
  // 切掉已通过校验的状态，否则重新填别的账号时表单还显示旧的错误
  formRef.value?.clearValidate()
}

/**
 * 一键登录：填入凭据后直接走登录流程。
 *
 * <p>复用 {@link onSubmit} 的完整链路（校验 → 请求 → setLogin → 跳转），
 * 而不是另写一套 —— 否则两处逻辑会各改各的，早晚不一致。
 */
async function quickLogin(acc) {
  fillDemo(acc)
  demoLoading.value = acc.username
  try {
    await onSubmit()
  } finally {
    demoLoading.value = null
  }
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
  /* 演示账号卡片要放账号/密码/权限说明，400px 太窄会挤成两行 */
  max-width: 520px;
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
  /* 文字过长会折行并压住分隔线，锁成单行并留足左右空间 */
  white-space: nowrap;
  padding: 0 10px;
}
/* ---- 演示账号卡片 ---- */
.demo-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 10px;
}
.demo-card {
  border: 1px solid var(--ec-border);
  border-radius: 8px;
  padding: 11px 12px;
  cursor: pointer;
  transition: all 0.16s;
  background: #fafbfc;
  /* 整卡可点，给手型光标明确暗示 */
  user-select: none;
}
.demo-card:hover {
  border-color: var(--ec-primary);
  background: #fef7f7;
  transform: translateY(-1px);
  box-shadow: 0 3px 10px rgba(228, 57, 60, 0.12);
}
.demo-card.active {
  border-color: var(--ec-primary);
  background: #fef0f0;
}

.demo-head {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  margin-bottom: 8px;
}
.demo-icon {
  font-size: 17px;
  margin-top: 1px;
  flex-shrink: 0;
}
.demo-role {
  flex: 1;
  min-width: 0;
}
.role-name {
  font-size: 13px;
  font-weight: 600;
  color: var(--ec-text);
  line-height: 1.4;
}
.role-desc {
  font-size: 11px;
  color: var(--ec-text-light);
  line-height: 1.5;
  margin-top: 2px;
}
.demo-badge {
  flex-shrink: 0;
  transform: scale(0.88);
  transform-origin: top right;
}

/* 凭据区：等宽字体让账号密码对齐，便于对照查看 */
.demo-cred {
  display: flex;
  gap: 12px;
  background: #fff;
  border: 1px solid #eef0f3;
  border-radius: 5px;
  padding: 5px 8px;
  margin-bottom: 8px;
}
.cred-item {
  display: flex;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}
.cred-item em {
  font-style: normal;
  color: var(--ec-text-light);
}
.cred-item code {
  font-family: 'SFMono-Regular', Consolas, 'Liberation Mono', monospace;
  font-size: 11px;
  color: var(--ec-text);
  background: none;
  padding: 0;
}

.demo-actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
}
.demo-actions :deep(.el-button + .el-button) {
  margin-left: 0;
}

.demo-note {
  display: flex;
  align-items: flex-start;
  gap: 5px;
  margin: 14px 0 0;
  font-size: 11px;
  line-height: 1.6;
  color: var(--ec-text-light);
}
.demo-note :deep(.el-icon) {
  margin-top: 2px;
  flex-shrink: 0;
}

/* ---- 移动端：演示卡片改单列，避免窄屏挤成三行 ---- */
@media (max-width: 560px) {
  .login-card {
    padding: 26px 18px 20px;
  }
  .demo-grid {
    grid-template-columns: 1fr;
  }
}
</style>
