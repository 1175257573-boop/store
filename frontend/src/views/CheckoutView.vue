<template>
  <div class="checkout container">
    <h2 class="page-title">确认订单</h2>

    <div v-loading="loading">
      <!-- 收货地址 -->
      <div class="card-box block">
        <div class="block-head">
          <h3>收货地址</h3>
          <el-button type="primary" link @click="router.push('/address')">
            管理收货地址
          </el-button>
        </div>

        <div v-if="addresses.length" class="address-list">
          <div
            v-for="a in addresses"
            :key="a.id"
            class="address-item"
            :class="{ active: selectedAddressId === a.id }"
            @click="selectedAddressId = a.id"
          >
            <div class="line1">
              <b>{{ a.receiver }}</b>
              <span class="phone">{{ a.phone }}</span>
              <el-tag v-if="a.isDefault === 1" type="danger" size="small">默认</el-tag>
            </div>
            <div class="line2">{{ a.province }}{{ a.city }}{{ a.district }}{{ a.detail }}</div>
            <el-icon v-if="selectedAddressId === a.id" class="check"><CircleCheckFilled /></el-icon>
          </div>
        </div>
        <el-empty v-else description="还没有收货地址" :image-size="80">
          <el-button type="danger" @click="router.push('/address')">去添加地址</el-button>
        </el-empty>
      </div>

      <!-- 商品清单 -->
      <div class="card-box block">
        <h3>商品清单</h3>
        <div v-for="g in groupedItems" :key="g.seller" class="group">
          <div class="group-title">{{ g.seller }}</div>
          <div v-for="it in g.items" :key="it.productId" class="goods-row">
            <img :src="it.productImage" :alt="it.productName" class="cover" />
            <div class="info">
              <div class="name">{{ it.productName }}</div>
              <div class="sub">{{ it.productSubtitle }}</div>
            </div>
            <div class="price">¥{{ it.productPrice }}</div>
            <div class="qty">×{{ it.quantity }}</div>
            <div class="subtotal price">¥{{ it.subtotal }}</div>
          </div>
        </div>
      </div>

      <!-- 备注与金额 -->
      <div class="card-box block">
        <h3>订单备注</h3>
        <el-input
          v-model="remark"
          type="textarea"
          :rows="2"
          maxlength="200"
          show-word-limit
          placeholder="选填，可填写对商家的额外要求"
        />

        <div class="amount-row">
          <div class="count-info">
            共 {{ totalCount }} 件商品
          </div>
          <div class="amounts">
            <span>商品总额：<b>¥{{ goodsAmount }}</b></span>
            <span>运费：<b class="free">免运费</b></span>
            <span class="final">
              实付金额：<b class="price">¥{{ goodsAmount }}</b>
            </span>
          </div>
        </div>
      </div>

      <!-- 提交栏 -->
      <div class="submit-bar">
        <el-button @click="router.back()">返回</el-button>
        <div class="right">
          <span class="label">应付：<b class="price total">¥{{ goodsAmount }}</b></span>
          <el-button
            type="danger"
            size="large"
            :loading="submitting"
            :disabled="!selectedAddressId || !items.length"
            @click="onSubmit"
          >
            提交订单
          </el-button>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { CircleCheckFilled } from '@element-plus/icons-vue'
import { getAddressList, createOrder } from '@/api'

const route = useRoute()
const router = useRouter()

const loading = ref(false)
const submitting = ref(false)
const addresses = ref([])
const selectedAddressId = ref(null)
const items = ref([])
const remark = ref('')
const source = ref('cart')

const totalCount = computed(() =>
  items.value.reduce((sum, i) => sum + i.quantity, 0)
)
const goodsAmount = computed(() =>
  items.value
    .reduce((sum, i) => sum + Number(i.subtotal || 0), 0)
    .toFixed(2)
)

/**
 * 按店铺分组展示。
 * 本项目没有店铺概念，用「全部商品」作为单一分组，
 * 结构保留是为了后续接入多商家时无需改页面。
 */
const groupedItems = computed(() => {
  if (!items.value.length) return []
  return [{ seller: '优选自营', items: items.value }]
})

async function loadData() {
  loading.value = true
  try {
    // 地址
    const addrRes = await getAddressList()
    addresses.value = addrRes.data || []
    if (addresses.value.length) {
      const def = addresses.value.find(a => a.isDefault === 1) || addresses.value[0]
      selectedAddressId.value = def.id
    }

    // 商品：来源不同，取数位置不同
    if (route.query.source === 'buyNow') {
      // 立即购买：参数存在 sessionStorage（经详情页写入）
      const raw = sessionStorage.getItem('buyNow')
      if (!raw) {
        ElMessage.warning('立即购买信息已失效，请重新选择商品')
        router.push('/')
        return
      }
      const buyNow = JSON.parse(raw)
      source.value = 'buyNow'
      items.value = [
        {
          productId: buyNow.productId,
          productName: '（正在获取商品信息）',
          quantity: buyNow.quantity,
          productPrice: 0,
          subtotal: 0
        }
      ]
      // 从 sessionStorage 只有 id 和数量，需要回查商品详情补齐价格与名称
      const { getProductDetail } = await import('@/api')
      const detail = await getProductDetail(buyNow.productId)
      const p = detail.data
      items.value[0] = {
        productId: p.id,
        productName: p.name,
        productImage: p.mainImage,
        productSubtitle: p.subtitle,
        productPrice: p.price,
        quantity: buyNow.quantity,
        subtotal: (p.price * buyNow.quantity).toFixed(2)
      }
    } else {
      // 从购物车结算：读取当前已勾选条目
      source.value = 'cart'
      const { getCart } = await import('@/api')
      const cartRes = await getCart()
      const checked = (cartRes.data || []).filter(i => i.checked === 1 && i.stockEnough)
      if (!checked.length) {
        ElMessage.warning('没有可结算的商品')
        router.push('/cart')
        return
      }
      items.value = checked
    }
  } catch (e) {
    items.value = []
  } finally {
    loading.value = false
  }
}

async function onSubmit() {
  if (!selectedAddressId.value) {
    ElMessage.warning('请选择收货地址')
    return
  }
  submitting.value = true
  try {
    const payload = {
      source: source.value,
      addressId: selectedAddressId.value,
      remark: remark.value || null
    }
    if (source.value === 'buyNow') {
      payload.items = items.value.map(i => ({
        productId: i.productId,
        quantity: i.quantity
      }))
    }
    const res = await createOrder(payload)
    ElMessage.success('下单成功，请尽快完成支付')
    sessionStorage.removeItem('buyNow')
    window.dispatchEvent(new Event('cart-change'))
    router.push(`/order/${res.data.id}`)
  } catch (e) {
    // 失败提示由拦截器处理
  } finally {
    submitting.value = false
  }
}

onMounted(loadData)
</script>

<style scoped>
.checkout {
  padding-top: 20px;
}
.page-title {
  font-size: 20px;
  margin: 0 0 16px;
}
.block {
  margin-bottom: 16px;
}
.block h3 {
  font-size: 16px;
  margin: 0 0 14px;
}
.block-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}
.block-head h3 {
  margin: 0;
}

.address-list {
  display: grid;
  grid-template-columns: repeat(2, 1fr);
  gap: 12px;
}
@media (max-width: 800px) {
  .address-list {
    grid-template-columns: 1fr;
  }
}
.address-item {
  border: 1px solid var(--ec-border);
  border-radius: 8px;
  padding: 12px 14px;
  cursor: pointer;
  position: relative;
  transition: all 0.2s;
}
.address-item:hover {
  border-color: var(--ec-primary);
}
.address-item.active {
  border-color: var(--ec-primary);
  background: #fff8f8;
}
.line1 {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 6px;
  font-size: 14px;
}
.line2 {
  font-size: 13px;
  color: var(--ec-text-light);
  line-height: 1.5;
}
.check {
  position: absolute;
  top: 10px;
  right: 10px;
  color: var(--ec-primary);
  font-size: 18px;
}

.group-title {
  font-size: 13px;
  color: var(--ec-text-light);
  padding-bottom: 8px;
  border-bottom: 1px dashed var(--ec-border);
  margin-bottom: 4px;
}
.goods-row {
  display: grid;
  grid-template-columns: 70px 1fr 90px 50px 90px;
  align-items: center;
  gap: 12px;
  padding: 12px 0;
  border-bottom: 1px solid #f5f5f5;
}
.goods-row:last-child {
  border-bottom: none;
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
  color: var(--ec-text-light);
  text-align: center;
}
.subtotal {
  text-align: right;
  font-size: 15px;
}

.amount-row {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-top: 18px;
  padding-top: 16px;
  border-top: 1px solid var(--ec-border);
  flex-wrap: wrap;
  gap: 12px;
}
.count-info {
  color: var(--ec-text-light);
  font-size: 13px;
}
.amounts {
  display: flex;
  gap: 20px;
  font-size: 13px;
  align-items: center;
  flex-wrap: wrap;
}
.amounts .free {
  color: #67c23a;
}
.amounts .final {
  font-size: 15px;
}
.amounts .final b {
  font-size: 22px;
}

.submit-bar {
  position: sticky;
  bottom: 0;
  background: #fff;
  border-radius: var(--ec-radius);
  padding: 14px 20px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  box-shadow: 0 -2px 12px rgba(0, 0, 0, 0.06);
}
.submit-bar .right {
  display: flex;
  align-items: center;
  gap: 20px;
}
.submit-bar .label {
  font-size: 14px;
  color: var(--ec-text-light);
}
.total {
  font-size: 24px;
  margin-left: 6px;
}
</style>
