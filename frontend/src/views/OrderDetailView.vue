<template>
  <div v-loading="loading" class="detail container">
    <template v-if="order">
      <div class="page-head">
        <el-button link @click="router.push('/orders')">
          <el-icon><ArrowLeft /></el-icon>返回订单列表
        </el-button>
      </div>

      <!-- 状态提示条 -->
      <div class="status-bar" :class="statusClass">
        <el-icon class="icon"><component :is="statusIcon" /></el-icon>
        <div>
          <div class="status-text">{{ statusHint }}</div>
          <div class="status-sub">{{ statusSub }}</div>
        </div>
        <div class="actions">
          <el-button v-if="order.status === 0" type="danger" @click="onPay">立即支付</el-button>
          <el-button v-if="order.status === 0" @click="onCancel">取消订单</el-button>
          <el-button v-if="order.status === 2" type="danger" @click="onConfirm">
            确认收货
          </el-button>
        </div>
      </div>

      <div class="card-box block">
        <h3>收货信息</h3>
        <div class="recv">
          <div class="line1">
            <b>{{ order.receiver }}</b>
            <span class="phone">{{ order.phone }}</span>
          </div>
          <div class="line2">{{ order.address }}</div>
        </div>
        <div v-if="order.remark" class="remark">备注：{{ order.remark }}</div>
      </div>

      <div class="card-box block">
        <h3>商品信息</h3>
        <div v-for="it in order.items" :key="it.productId" class="goods-row">
          <img :src="it.productImage" :alt="it.productName" class="cover" />
          <div class="info">
            <div class="name">{{ it.productName }}</div>
            <div class="sub">单价 ¥{{ it.productPrice }}</div>
          </div>
          <div class="qty">×{{ it.quantity }}</div>
          <div class="subtotal price">¥{{ it.subtotal }}</div>
        </div>

        <div class="amount">
          <div class="row">
            <span>商品总额</span><b>¥{{ order.totalAmount }}</b>
          </div>
          <div class="row">
            <span>运费</span><b class="free">免运费</b>
          </div>
          <div class="row final">
            <span>实付金额</span><b class="price">¥{{ order.payAmount }}</b>
          </div>
        </div>
      </div>

      <div class="card-box block">
        <h3>订单信息</h3>
        <el-descriptions :column="1" border size="small">
          <el-descriptions-item label="订单编号">{{ order.orderNo }}</el-descriptions-item>
          <el-descriptions-item label="订单状态">
            <el-tag :type="tagType" size="small">{{ order.statusText }}</el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="下单时间">{{ order.createTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="支付时间">{{ order.payTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="发货时间">{{ order.shipTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="完成时间">{{ order.finishTime || '-' }}</el-descriptions-item>
          <el-descriptions-item label="取消时间">{{ order.cancelTime || '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  ArrowLeft,
  Wallet,
  Van,
  CircleCheckFilled,
  CloseBold
} from '@element-plus/icons-vue'
import { getOrderDetail, payOrder, cancelOrder, confirmOrder } from '@/api'

const route = useRoute()
const router = useRouter()

const loading = ref(true)
const order = ref(null)

const tagType = computed(
  () => ({ 0: 'warning', 1: 'primary', 2: 'primary', 3: 'success', 4: 'info' }[
    order.value?.status
  ] || 'info')
)

const statusIcon = computed(
  () => ({ 0: Wallet, 1: Van, 2: Van, 3: CircleCheckFilled, 4: CloseBold }[
    order.value?.status
  ] || Wallet)
)

const statusClass = computed(
  () => 'status-' + (order.value?.status ?? 0)
)

const statusHint = computed(
  () =>
    ({
      0: '订单待付款',
      1: '付款成功，等待商家发货',
      2: '商品已发出，请注意查收',
      3: '订单已完成，感谢您的购买',
      4: '订单已取消'
    }[order.value?.status] || '订单详情')
)

const statusSub = computed(
  () =>
    ({
      0: '请在 30 分钟内完成支付，超时订单将自动取消',
      1: '商家正在备货，请耐心等待',
      2: '收到商品后请点击「确认收货」',
      3: '如有售后问题，可在订单完成后联系客服',
      4: '商品库存已释放'
    }[order.value?.status] || '')
)

async function loadDetail() {
  loading.value = true
  try {
    const res = await getOrderDetail(route.params.id)
    order.value = res.data
  } catch (e) {
    order.value = null
  } finally {
    loading.value = false
  }
}

async function onPay() {
  try {
    await ElMessageBox.confirm(
      `将模拟支付订单 ${order.value.orderNo}，金额 ¥${order.value.payAmount}（演示环境无真实扣款）`,
      '确认支付',
      { type: 'info', confirmButtonText: '确认支付' }
    )
  } catch {
    return
  }
  await payOrder(order.value.id)
  ElMessage.success('支付成功')
  loadDetail()
}

async function onCancel() {
  try {
    await ElMessageBox.confirm('取消后订单不可恢复，商品库存将被释放。确定取消吗？', '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  await cancelOrder(order.value.id)
  ElMessage.success('订单已取消')
  loadDetail()
}

async function onConfirm() {
  try {
    await ElMessageBox.confirm('请确认已收到商品。', '确认收货', { type: 'success' })
  } catch {
    return
  }
  await confirmOrder(order.value.id)
  ElMessage.success('已确认收货')
  loadDetail()
}

onMounted(loadDetail)
</script>

<style scoped>
.detail {
  padding-top: 16px;
}
.page-head {
  margin-bottom: 12px;
}

.status-bar {
  display: flex;
  align-items: center;
  gap: 16px;
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 20px;
  margin-bottom: 16px;
  border-left: 4px solid #909399;
}
.status-bar .icon {
  font-size: 32px;
  color: #909399;
}
.status-text {
  font-size: 18px;
  font-weight: 600;
  margin-bottom: 4px;
}
.status-sub {
  font-size: 13px;
  color: var(--ec-text-light);
}
.status-bar .actions {
  margin-left: auto;
  display: flex;
  gap: 10px;
}

.status-0 {
  border-left-color: #e6a23c;
}
.status-0 .icon {
  color: #e6a23c;
}
.status-1,
.status-2 {
  border-left-color: #409eff;
}
.status-1 .icon,
.status-2 .icon {
  color: #409eff;
}
.status-3 {
  border-left-color: #67c23a;
}
.status-3 .icon {
  color: #67c23a;
}

.block {
  margin-bottom: 16px;
}
.block h3 {
  font-size: 16px;
  margin: 0 0 14px;
}
.recv .line1 {
  display: flex;
  gap: 12px;
  align-items: center;
  margin-bottom: 6px;
}
.recv .line2 {
  font-size: 13px;
  color: var(--ec-text-light);
  line-height: 1.5;
}
.remark {
  margin-top: 10px;
  padding: 8px 12px;
  background: #f7f7f7;
  border-radius: 6px;
  font-size: 13px;
  color: var(--ec-text-light);
}

.goods-row {
  display: grid;
  grid-template-columns: 70px 1fr 60px 90px;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f5f5f5;
}
.cover {
  width: 70px;
  height: 70px;
  object-fit: cover;
  border-radius: 6px;
  background: #f2f3f5;
}
.info .name {
  font-size: 14px;
  margin-bottom: 4px;
}
.info .sub {
  font-size: 12px;
  color: var(--ec-text-light);
}
.qty {
  text-align: center;
  color: var(--ec-text-light);
}
.subtotal {
  text-align: right;
  font-size: 15px;
}

.amount {
  margin-top: 16px;
  padding-top: 14px;
  border-top: 1px solid var(--ec-border);
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 6px;
}
.amount .row {
  display: flex;
  gap: 20px;
  font-size: 13px;
  color: var(--ec-text-light);
}
.amount .free {
  color: #67c23a;
}
.amount .final {
  font-size: 15px;
  color: var(--ec-text);
}
.amount .final b {
  font-size: 22px;
}
</style>
