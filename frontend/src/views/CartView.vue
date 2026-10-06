<template>
  <div class="cart container">
    <h2 class="page-title">购物车</h2>

    <div v-loading="loading">
      <template v-if="items.length">
        <div class="card-box">
          <!-- 全选行 -->
          <div class="toolbar">
            <el-checkbox
              :model-value="allChecked"
              :indeterminate="someChecked"
              @change="onToggleAll"
            >
              全选
            </el-checkbox>
            <span class="total-info">
              已选 <b>{{ checkedCount }}</b> 件商品，合计
              <b class="price">¥{{ checkedTotal }}</b>
            </span>
            <el-button type="danger" @click="onDeleteChecked">删除选中</el-button>
          </div>

          <el-divider />

          <!-- 商品条目 -->
          <div
            v-for="item in items"
            :key="item.cartId"
            class="cart-item"
            :class="{ disabled: !item.stockEnough }"
          >
            <el-checkbox
              :model-value="item.checked === 1"
              :disabled="!item.stockEnough"
              @change="v => onToggleChecked(item, v)"
            />

            <img
              :src="item.productImage"
              :alt="item.productName"
              class="cover"
              @click="goDetail(item.productId)"
            />

            <div class="info">
              <div class="name" @click="goDetail(item.productId)">
                {{ item.productName }}
              </div>
              <div class="sub">{{ item.productSubtitle }}</div>
              <div v-if="!item.stockEnough" class="warn">
                库存不足，当前仅剩 {{ item.stock }} 件
              </div>
            </div>

            <div class="price">
              <span class="price-symbol">¥</span>
              <span class="price-value">{{ item.productPrice }}</span>
            </div>

            <el-input-number
              :model-value="item.quantity"
              :min="1"
              :max="Math.min(item.stock || 1, 999)"
              size="small"
              @change="v => onQtyChange(item, v)"
            />

            <div class="subtotal price">¥{{ item.subtotal }}</div>

            <el-button type="danger" link @click="onDelete(item)">删除</el-button>
          </div>
        </div>

        <!-- 结算栏 -->
        <div class="settle-bar">
          <el-checkbox
            :model-value="allChecked"
            :indeterminate="someChecked"
            @change="onToggleAll"
          >
            全选
          </el-checkbox>
          <div class="right">
            <span class="total-info">
              已选 <b>{{ checkedCount }}</b> 件，合计
              <b class="price total">¥{{ checkedTotal }}</b>
            </span>
            <el-button
              type="danger"
              size="large"
              :disabled="checkedCount === 0"
              @click="goCheckout"
            >
              去结算（{{ checkedCount }}）
            </el-button>
          </div>
        </div>
      </template>

      <el-empty v-else-if="!loading" description="购物车还是空的">
        <el-button type="danger" @click="router.push('/')">去逛逛</el-button>
      </el-empty>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getCart,
  updateCartQuantity,
  updateCartChecked,
  updateAllCartChecked,
  removeCartItem,
  removeCartBatch
} from '@/api'

const router = useRouter()
const loading = ref(false)
const items = ref([])

const checkedItems = computed(() => items.value.filter(i => i.checked === 1 && i.stockEnough))
const checkedCount = computed(() =>
  checkedItems.value.reduce((sum, i) => sum + i.quantity, 0)
)
const checkedTotal = computed(() =>
  checkedItems.value
    .reduce((sum, i) => sum + Number(i.subtotal || 0), 0)
    .toFixed(2)
)
const allChecked = computed(
  () => items.value.length > 0 && items.value.every(i => i.checked === 1)
)
const someChecked = computed(
  () => !allChecked.value && items.value.some(i => i.checked === 1)
)

async function loadCart() {
  loading.value = true
  try {
    const res = await getCart()
    items.value = res.data || []
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

function refreshBadge() {
  window.dispatchEvent(new Event('cart-change'))
}

async function onToggleAll(v) {
  await updateAllCartChecked(v ? 1 : 0)
  await loadCart()
}

async function onToggleChecked(item, v) {
  await updateCartChecked(item.cartId, v ? 1 : 0)
  item.checked = v ? 1 : 0
  refreshBadge()
}

async function onQtyChange(item, v) {
  if (!v) return
  if (v > item.stock) {
    ElMessage.error(`库存仅剩 ${item.stock} 件`)
    return
  }
  try {
    await updateCartQuantity(item.cartId, v)
    item.quantity = v
    item.subtotal = (item.productPrice * v).toFixed(2)
    item.stockEnough = v <= item.stock
  } catch (e) {
    await loadCart()
  }
}

async function onDelete(item) {
  try {
    await ElMessageBox.confirm(`确定要删除「${item.productName}」吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await removeCartItem(item.cartId)
  ElMessage.success('已删除')
  await loadCart()
  refreshBadge()
}

async function onDeleteChecked() {
  const ids = checkedItems.value.map(i => i.cartId)
  if (!ids.length) {
    ElMessage.warning('请先勾选要删除的商品')
    return
  }
  try {
    await ElMessageBox.confirm(`确定要删除选中的 ${ids.length} 件商品吗？`, '提示', { type: 'warning' })
  } catch {
    return
  }
  await removeCartBatch(ids)
  ElMessage.success('已批量删除')
  await loadCart()
  refreshBadge()
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

function goCheckout() {
  if (!checkedCount.value) {
    ElMessage.warning('请先勾选要结算的商品')
    return
  }
  router.push('/checkout?source=cart')
}

onMounted(loadCart)
</script>

<style scoped>
.cart {
  padding-top: 20px;
}
.page-title {
  font-size: 20px;
  margin: 0 0 16px;
}

.toolbar {
  display: flex;
  align-items: center;
  gap: 20px;
}
.total-info {
  font-size: 14px;
  color: var(--ec-text-light);
}
.total-info b {
  color: var(--ec-primary);
}

.cart-item {
  display: grid;
  grid-template-columns: 24px 90px 1fr 110px 130px 100px 60px;
  align-items: center;
  gap: 14px;
  padding: 16px 0;
  border-bottom: 1px solid var(--ec-border);
}
.cart-item:last-child {
  border-bottom: none;
}
.cart-item.disabled {
  opacity: 0.55;
}
.cover {
  width: 90px;
  height: 90px;
  object-fit: cover;
  border-radius: 6px;
  background: #f2f3f5;
  cursor: pointer;
}
.info .name {
  font-size: 14px;
  line-height: 1.5;
  margin-bottom: 4px;
  cursor: pointer;
}
.info .name:hover {
  color: var(--ec-primary);
}
.info .sub {
  font-size: 12px;
  color: var(--ec-text-light);
}
.info .warn {
  font-size: 12px;
  color: #e6a23c;
  margin-top: 4px;
}
.subtotal {
  text-align: right;
  font-size: 15px;
}

.settle-bar {
  position: sticky;
  bottom: 0;
  margin-top: 16px;
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 -2px 12px rgba(0, 0, 0, 0.06);
}
.settle-bar .right {
  display: flex;
  align-items: center;
  gap: 20px;
}
.total {
  font-size: 22px;
  margin: 0 4px;
}
</style>
