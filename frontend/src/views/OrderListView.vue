<template>
  <div class="orders container">
    <h2 class="page-title">我的订单</h2>

    <!-- 状态筛选 -->
    <el-tabs v-model="activeStatus" @tab-change="onTabChange">
      <el-tab-pane label="全部" name="-1" />
      <el-tab-pane label="待付款" name="0" />
      <el-tab-pane label="待发货" name="1" />
      <el-tab-pane label="待收货" name="2" />
      <el-tab-pane label="已完成" name="3" />
      <el-tab-pane label="已取消" name="4" />
    </el-tabs>

    <div v-loading="loading">
      <template v-if="orders.length">
        <div v-for="o in orders" :key="o.id" class="card-box order-card">
          <div class="order-head">
            <div class="left">
              <span class="no">订单号：{{ o.orderNo }}</span>
              <span class="time">{{ o.createTime }}</span>
            </div>
            <el-tag :type="statusTagType(o.status)" size="small">{{ o.statusText }}</el-tag>
          </div>

          <div class="goods">
            <div v-for="it in o.items" :key="it.productId" class="goods-item">
              <img :src="it.productImage" :alt="it.productName" class="cover" />
              <div class="info">
                <div class="name">{{ it.productName }}</div>
                <div class="sub">¥{{ it.productPrice }} × {{ it.quantity }}</div>
              </div>
            </div>
          </div>

          <div class="order-foot">
            <div class="amount">
              共 <b>{{ o.totalQuantity }}</b> 件，实付
              <b class="price">¥{{ o.payAmount }}</b>
            </div>
            <div class="actions">
              <el-button
                v-if="o.status === 0"
                type="danger"
                @click="onPay(o)"
              >
                立即支付
              </el-button>
              <el-button v-if="o.status === 0" @click="onCancel(o)">取消订单</el-button>
              <el-button v-if="o.status === 2" type="danger" @click="onConfirm(o)">
                确认收货
              </el-button>
              <el-button @click="goDetail(o.id)">查看详情</el-button>
            </div>
          </div>
        </div>

        <div v-if="total > pageSize" class="pagination">
          <el-pagination
            v-model:current-page="pageNum"
            :page-size="pageSize"
            :total="total"
            layout="prev, pager, next"
            background
            @current-change="loadOrders"
          />
        </div>
      </template>

      <el-empty
        v-else-if="!loading"
        :description="activeStatus === '-1' ? '还没有任何订单' : '该状态下暂无订单'"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getOrderList, payOrder, cancelOrder, confirmOrder } from '@/api'

const router = useRouter()
const pageSize = 5
const pageNum = ref(1)
const activeStatus = ref('-1')
const loading = ref(false)
const orders = ref([])
const total = ref(0)

function statusTagType(status) {
  return (
    {
      0: 'warning',
      1: 'primary',
      2: 'primary',
      3: 'success',
      4: 'info'
    }[status] || 'info'
  )
}

async function loadOrders() {
  loading.value = true
  try {
    const res = await getOrderList({
      pageNum: pageNum.value,
      pageSize,
      status: activeStatus.value === '-1' ? undefined : Number(activeStatus.value)
    })
    orders.value = res.data.records || []
    total.value = Number(res.data.total) || 0
  } catch (e) {
    orders.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function onTabChange() {
  pageNum.value = 1
  loadOrders()
}

function goDetail(id) {
  router.push(`/order/${id}`)
}

async function onPay(order) {
  try {
    await ElMessageBox.confirm(
      `将模拟支付订单 ${order.orderNo}，金额 ¥${order.payAmount}（演示环境无真实扣款）`,
      '确认支付',
      { type: 'info', confirmButtonText: '确认支付', cancelButtonText: '取消' }
    )
  } catch {
    return
  }
  await payOrder(order.id)
  ElMessage.success('支付成功')
  loadOrders()
}

async function onCancel(order) {
  try {
    await ElMessageBox.confirm('取消后订单不可恢复，且商品库存将被释放。确定取消吗？', '提示', {
      type: 'warning'
    })
  } catch {
    return
  }
  await cancelOrder(order.id)
  ElMessage.success('订单已取消')
  loadOrders()
}

async function onConfirm(order) {
  try {
    await ElMessageBox.confirm('请确认已收到商品。', '确认收货', { type: 'success' })
  } catch {
    return
  }
  await confirmOrder(order.id)
  ElMessage.success('已确认收货')
  loadOrders()
}

onMounted(loadOrders)
</script>

<style scoped>
.orders {
  padding-top: 20px;
}
.page-title {
  font-size: 20px;
  margin: 0 0 12px;
}
.order-card {
  margin-bottom: 16px;
}
.order-head {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-bottom: 12px;
  border-bottom: 1px solid var(--ec-border);
  font-size: 13px;
  color: var(--ec-text-light);
}
.order-head .no {
  margin-right: 16px;
  color: var(--ec-text);
}

.goods {
  padding: 14px 0;
}
.goods-item {
  display: flex;
  gap: 12px;
  padding: 8px 0;
}
.cover {
  width: 70px;
  height: 70px;
  object-fit: cover;
  border-radius: 6px;
  background: #f2f3f5;
  flex-shrink: 0;
}
.info .name {
  font-size: 14px;
  margin-bottom: 4px;
}
.info .sub {
  font-size: 12px;
  color: var(--ec-text-light);
}

.order-foot {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding-top: 12px;
  border-top: 1px solid var(--ec-border);
  flex-wrap: wrap;
  gap: 10px;
}
.amount {
  font-size: 14px;
  color: var(--ec-text-light);
}
.actions {
  display: flex;
  gap: 8px;
}
.pagination {
  display: flex;
  justify-content: center;
  margin-top: 20px;
}
</style>
