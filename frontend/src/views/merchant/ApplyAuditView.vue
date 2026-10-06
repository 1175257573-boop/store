<template>
  <div class="apply-audit">
    <div class="page-head"><h2>入驻审核</h2></div>

    <el-tabs v-model="activeStatus" @tab-change="loadList">
      <el-tab-pane label="待审核" name="0" />
      <el-tab-pane label="已通过" name="1" />
      <el-tab-pane label="已拒绝" name="2" />
    </el-tabs>

    <div class="filter-bar">
      <el-input v-model="keyword" placeholder="店铺名 / 联系人" clearable
                style="width: 220px" @keyup.enter="loadList" />
      <el-button @click="loadList">查询</el-button>
    </div>

    <el-table v-loading="loading" :data="list" size="small" stripe>
      <el-table-column prop="shopName" label="店铺名称" min-width="140" />
      <el-table-column prop="contactName" label="联系人" width="100" />
      <el-table-column prop="contactPhone" label="联系电话" width="120" />
      <el-table-column label="类目" width="80">
        <template #default="{ row }">{{ row.businessType === 2 ? '企业' : '个人' }}</template>
      </el-table-column>
      <el-table-column prop="licenseNo" label="营业执照" width="140">
        <template #default="{ row }">{{ row.licenseNo || '-' }}</template>
      </el-table-column>
      <el-table-column prop="shopDesc" label="店铺简介" min-width="160" show-overflow-tooltip />
      <el-table-column label="状态" width="90">
        <template #default="{ row }">
          <el-tag :type="{ 0: 'warning', 1: 'success', 2: 'danger' }[row.status]" size="small">
            {{ { 0: '待审核', 1: '已通过', 2: '已拒绝' }[row.status] }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="130" fixed="right">
        <template #default="{ row }">
          <template v-if="row.status === 0">
            <el-button type="danger" size="small" link @click="onAudit(row, true)">通过</el-button>
            <el-button size="small" link @click="onAudit(row, false)">拒绝</el-button>
          </template>
          <span v-else class="sub">{{ row.auditRemark || '已处理' }}</span>
        </template>
      </el-table-column>
      <template #empty><el-empty description="暂无申请" :image-size="80" /></template>
    </el-table>

    <el-dialog v-model="remarkVisible" :title="pass ? '通过入驻申请' : '拒绝入驻申请'"
               width="420px">
      <el-input v-model="remark" type="textarea" :rows="3" maxlength="200" show-word-limit
                :placeholder="pass ? '可填写审核意见（选填）' : '请填写拒绝原因'" />
      <template #footer>
        <el-button @click="remarkVisible = false">取消</el-button>
        <el-button :type="pass ? 'danger' : 'warning'" :loading="submitting"
                   @click="submit">确认{{ pass ? '通过' : '拒绝' }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { listApplies, auditApply } from '@/api/merchant'

const loading = ref(false)
const submitting = ref(false)
const remarkVisible = ref(false)
const list = ref([])
const keyword = ref('')
const activeStatus = ref('0')
const pass = ref(true)
const remark = ref('')
const current = ref(null)

async function loadList() {
  loading.value = true
  try {
    const res = await listApplies({
      status: activeStatus.value === '-1' ? undefined : Number(activeStatus.value),
      keyword: keyword.value || undefined
    })
    list.value = res.data || []
  } catch (e) { /* 拦截器已提示 */ } finally {
    loading.value = false
  }
}

function onAudit(row, isPass) {
  current.value = row
  pass.value = isPass
  remark.value = ''
  remarkVisible.value = true
}

async function submit() {
  if (!pass.value && !remark.value.trim()) {
    ElMessage.warning('拒绝必须填写原因')
    return
  }
  submitting.value = true
  try {
    await auditApply(current.value.id, pass.value, remark.value.trim() || null)
    ElMessage.success(pass.value ? '已通过，店铺已创建' : '已拒绝')
    remarkVisible.value = false
    loadList()
    // 通知侧边栏刷新待审红点，处理完少一条要立刻体现
    window.dispatchEvent(new Event('todo-changed'))
  } catch (e) { /* 拦截器已提示 */ } finally {
    submitting.value = false
  }
}

onMounted(loadList)
</script>

<style scoped>
.page-head { margin-bottom: 12px; }
.page-head h2 { margin: 0; font-size: 18px; }
.filter-bar { display: flex; gap: 10px; margin-bottom: 12px; }
.sub { font-size: 12px; color: var(--ec-text-light); }
</style>
