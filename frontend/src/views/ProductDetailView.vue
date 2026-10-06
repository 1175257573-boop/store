<template>
  <div v-loading="loading" class="detail container">
    <template v-if="product">
      <el-breadcrumb separator="/" class="crumb">
        <el-breadcrumb-item :to="{ path: '/' }">首页</el-breadcrumb-item>
        <el-breadcrumb-item :to="{ path: '/', query: { categoryId: product.categoryId } }">
          {{ product.categoryName }}
        </el-breadcrumb-item>
        <el-breadcrumb-item>商品详情</el-breadcrumb-item>
      </el-breadcrumb>

      <div class="card-box main">
        <!-- 左：商品图 -->
        <div class="gallery">
          <img :src="product.mainImage" :alt="product.name" class="main-image" />
        </div>

        <!-- 右：信息与操作 -->
        <div class="info">
          <h1 class="name">{{ product.name }}</h1>
          <p class="subtitle">{{ product.subtitle }}</p>

          <div class="price-box">
            <div class="price-row">
              <span class="price-label">价格</span>
              <span class="price">
                <span class="price-symbol">¥</span>
                <span class="price-value big">{{ currentPrice }}</span>
              </span>
              <span v-if="product.originPrice" class="price-origin">¥{{ product.originPrice }}</span>
            </div>
            <div class="meta">
              <span>累计销量 {{ product.sales }} 件</span>
              <el-divider direction="vertical" />
              <span>{{ product.viewCount }} 人看过</span>
            </div>
          </div>

          <div v-if="skuOptions.length > 1" class="sku-row">
            <span class="price-label">规格</span>
            <div class="sku-list">
              <button
                v-for="s in skuOptions"
                :key="s.id"
                type="button"
                class="sku-item"
                :class="{
                  active: selectedSku && selectedSku.id === s.id,
                  disabled: s.stock === 0
                }"
                :disabled="s.stock === 0"
                @click="selectSku(s)"
              >
                {{ s.specText }}
                <span v-if="s.stock === 0" class="sku-out">已售罄</span>
                <span v-else class="sku-price">¥{{ s.price }}</span>
              </button>
            </div>
          </div>

          <div class="stock-row">
            <span class="price-label">库存</span>
            <span :class="stockClass">{{ currentStock }} 件</span>
            <el-tag v-if="currentStock === 0" type="danger" size="small">已售罄</el-tag>
            <el-tag v-else-if="currentStock < 50" type="warning" size="small">仅剩少量</el-tag>
          </div>

          <div class="qty-row">
            <span class="price-label">数量</span>
            <el-input-number
              v-model="quantity"
              :min="1"
              :max="Math.min(currentStock || 1, 999)"
              :disabled="currentStock === 0"
            />
            <span class="subtotal-hint" v-if="currentStock > 0">
              小计 <b class="price">¥{{ subtotal }}</b>
            </span>
          </div>

          <div class="actions">
            <el-button
              type="warning"
              size="large"
              :disabled="currentStock === 0"
              @click="onAddCart"
            >
              加入购物车
            </el-button>
            <el-button
              type="danger"
              size="large"
              :disabled="currentStock === 0"
              @click="onBuyNow"
            >
              立即购买
            </el-button>
          </div>
        </div>
      </div>

      <!-- 商品详情文本 -->
      <div class="card-box desc-box">
        <h2 class="section-title" style="margin: 0 0 14px">商品详情</h2>
        <p class="description">{{ product.description }}</p>
      </div>

      <!-- 相关推荐 -->
      <div v-if="related.length" class="related">
        <h2 class="section-title">相关推荐</h2>
        <div class="grid">
          <div
            v-for="p in related"
            :key="p.id"
            class="product-card"
            @click="goDetail(p.id)"
          >
            <img :src="p.mainImage" :alt="p.name" class="cover" loading="lazy" />
            <div class="info">
              <div class="name">{{ p.name }}</div>
              <div class="sub">{{ p.subtitle }}</div>
              <div class="footer">
                <span class="price">
                  <span class="price-symbol">¥</span>
                  <span class="price-value">{{ p.price }}</span>
                </span>
                <span class="sales">已售 {{ p.sales }}</span>
              </div>
            </div>
          </div>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { ref, computed, onMounted, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { getProductDetail, getRelatedProducts, addToCart } from '@/api'
import { useUserStore } from '@/stores/user'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const loading = ref(true)
const product = ref(null)
const related = ref([])
const quantity = ref(1)
const selectedSku = ref(null)

// 可售规格。后端详情接口返回 skuList，空数组表示未配置多规格，
// 此时按商品本身的 price/stock 展示（单规格商品的老逻辑）。
const skuOptions = computed(() => product.value?.skuList || [])

// 当前价/库存跟随选中规格；未选（或无规格）时回落到商品主字段
const currentPrice = computed(() => {
  if (selectedSku.value) return Number(selectedSku.value.price).toFixed(2)
  return product.value ? Number(product.value.price).toFixed(2) : '0.00'
})

const currentStock = computed(() => {
  if (selectedSku.value) return selectedSku.value.stock || 0
  return product.value?.stock || 0
})

const subtotal = computed(() => {
  const p = Number(currentPrice.value)
  return (Number.isNaN(p) ? 0 : p * quantity.value).toFixed(2)
})

// 切换规格：数量上限要跟着新库存收敛，
// 否则会出现「选了只剩 1 件的规格，数量还是 5」导致下单失败
function selectSku(s) {
  if (s.stock === 0) return
  selectedSku.value = s
  if (quantity.value > s.stock) quantity.value = Math.max(1, s.stock)
}

const stockClass = computed(() => {
  const s = currentStock.value
  if (s === 0) return 'out-of-stock'
  if (s < 50) return 'low-stock'
  return ''
})

async function loadDetail() {
  loading.value = true
  try {
    const res = await getProductDetail(route.params.id)
    product.value = res.data
    quantity.value = 1
    // 默认选中第一个有货规格。必须挑有库存的：
    // 排在前面的规格可能已售罄，自动选中会让用户一进页面就看到「已售罄」。
    const skus = res.data?.skuList || []
    selectedSku.value = skus.find((s) => s.stock > 0) || null
    const rel = await getRelatedProducts(route.params.id, 5)
    related.value = rel.data || []
  } catch (e) {
    product.value = null
  } finally {
    loading.value = false
  }
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

async function onAddCart() {
  if (!userStore.isLogin) {
    ElMessage.warning('请先登录')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  // 库存校验用当前规格的库存，不是商品总库存 ——
  // 否则会出现「选了只剩 1 件的规格，校验却按商品总库存放行」，
  // 下单时才失败，用户已经点了加入购物车。
  if (currentStock.value < quantity.value) {
    ElMessage.error(`该规格仅剩 ${currentStock.value} 件`)
    return
  }
  try {
    // 注意：购物车接口目前只接受 productId，不接受 skuId，
    // 所以加购仍按商品维度记录。规格选择在本次改动里
    // 只影响「展示哪档价格 / 按哪档库存做校验」，
    // 真正按 SKU 落单需要后端购物车表加 skuId 字段。
    await addToCart({ productId: product.value.id, quantity: quantity.value })
    ElMessage.success('已加入购物车')
    // 通知顶栏刷新角标
    window.dispatchEvent(new Event('cart-change'))
  } catch (e) {
    // 提示已处理
  }
}

function onBuyNow() {
  if (!userStore.isLogin) {
    router.push({ path: '/login', query: { redirect: '/cart' } })
    return
  }
  // 通过 sessionStorage 传递立即购买参数，结算页读取
  sessionStorage.setItem(
    'buyNow',
    JSON.stringify({ productId: product.value.id, quantity: quantity.value })
  )
  router.push('/checkout?source=buyNow')
}

watch(() => route.params.id, loadDetail)
onMounted(loadDetail)
</script>

<style scoped>
.detail {
  padding-top: 20px;
}
.crumb {
  margin-bottom: 16px;
}
.main {
  display: grid;
  grid-template-columns: 440px 1fr;
  gap: 40px;
  margin-bottom: 20px;
}
@media (max-width: 900px) {
  .main {
    grid-template-columns: 1fr;
  }
}

.main-image {
  width: 100%;
  aspect-ratio: 1;
  object-fit: cover;
  border-radius: 8px;
  background: #f2f3f5;
}

.info .name {
  font-size: 22px;
  line-height: 1.4;
  margin: 0 0 8px;
}
.info .subtitle {
  color: var(--ec-text-light);
  font-size: 14px;
  margin: 0 0 20px;
}

.price-box {
  background: #fff8f8;
  border-radius: 8px;
  padding: 16px;
  margin-bottom: 20px;
}
.price-row {
  display: flex;
  align-items: baseline;
  gap: 8px;
}
.price-value.big {
  font-size: 30px;
}
.price-label {
  color: var(--ec-text-light);
  font-size: 13px;
  margin-right: 8px;
}
.meta {
  margin-top: 10px;
  font-size: 13px;
  color: var(--ec-text-light);
}

.stock-row,
.sku-row,
.qty-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  font-size: 14px;
}

/* 规格选择：横向排列，换行自动折行 */
.sku-row {
  align-items: flex-start;
}

.sku-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}

.sku-item {
  padding: 6px 12px;
  border: 1px solid #dcdfe6;
  border-radius: 4px;
  background: #fff;
  cursor: pointer;
  font-size: 13px;
  color: #555;
  transition: all 0.15s;
  display: inline-flex;
  align-items: center;
  gap: 6px;
}

.sku-item:hover:not(.disabled) {
  border-color: #e4393c;
  color: #e4393c;
}

.sku-item.active {
  border-color: #e4393c;
  background: #fef0f0;
  color: #e4393c;
}

.sku-item.disabled {
  cursor: not-allowed;
  color: #c0c4cc;
  background: #f7f8fa;
  text-decoration: line-through;
}

.sku-price {
  color: #e4393c;
  font-size: 12px;
}

.sku-item.disabled .sku-price,
.sku-out {
  color: #c0c4cc;
  font-size: 12px;
}
.low-stock {
  color: #e6a23c;
}
.out-of-stock {
  color: var(--ec-text-light);
  text-decoration: line-through;
}
.subtotal-hint {
  margin-left: 10px;
  font-size: 13px;
  color: var(--ec-text-light);
}

.actions {
  display: flex;
  gap: 12px;
  margin-top: 26px;
}
.actions .el-button {
  flex: 1;
  max-width: 180px;
  font-weight: 600;
}

.desc-box {
  margin-bottom: 20px;
}
.description {
  font-size: 14px;
  line-height: 1.9;
  color: #444;
  margin: 0;
  white-space: pre-wrap;
}

.related .grid {
  margin-top: 4px;
}
</style>
