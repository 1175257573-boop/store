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
                <span class="price-value big">{{ product.price }}</span>
              </span>
              <span v-if="product.originPrice" class="price-origin">¥{{ product.originPrice }}</span>
            </div>
            <div class="meta">
              <span>累计销量 {{ product.sales }} 件</span>
              <el-divider direction="vertical" />
              <span>{{ product.viewCount }} 人看过</span>
            </div>
          </div>

          <div class="stock-row">
            <span class="price-label">库存</span>
            <span :class="stockClass">{{ product.stock }} 件</span>
            <el-tag v-if="product.stock === 0" type="danger" size="small">已售罄</el-tag>
            <el-tag v-else-if="product.stock < 50" type="warning" size="small">仅剩少量</el-tag>
          </div>

          <div class="qty-row">
            <span class="price-label">数量</span>
            <el-input-number
              v-model="quantity"
              :min="1"
              :max="Math.min(product.stock || 1, 999)"
              :disabled="product.stock === 0"
            />
            <span class="subtotal-hint" v-if="product.stock > 0">
              小计 <b class="price">¥{{ subtotal }}</b>
            </span>
          </div>

          <div class="actions">
            <el-button
              type="warning"
              size="large"
              :disabled="product.stock === 0"
              @click="onAddCart"
            >
              加入购物车
            </el-button>
            <el-button
              type="danger"
              size="large"
              :disabled="product.stock === 0"
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

const subtotal = computed(() => {
  if (!product.value) return '0.00'
  return (product.value.price * quantity.value).toFixed(2)
})

const stockClass = computed(() => {
  const s = product.value?.stock || 0
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
  if (product.value.stock < quantity.value) {
    ElMessage.error(`库存仅剩 ${product.value.stock} 件`)
    return
  }
  try {
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
.qty-row {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 18px;
  font-size: 14px;
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
