<template>
  <div class="shop-page">
    <!-- ============ 店铺头信息 ============ -->
    <div class="shop-banner">
      <div class="container banner-inner">
        <div class="shop-avatar">
          {{ avatarText }}
        </div>
        <div class="shop-meta">
          <h1 class="shop-name">{{ shop.shopName || '店铺' }}</h1>
          <div class="shop-stats">
            <span class="stat">
              <el-icon><StarFilled /></el-icon>
              {{ shop.score || '5.00' }} 分
            </span>
            <el-divider direction="vertical" />
            <span class="stat">{{ shop.onShelfCount ?? 0 }} 件在售</span>
          </div>
          <p v-if="shop.shopDesc" class="shop-desc">{{ shop.shopDesc }}</p>
        </div>
        <div class="shop-actions">
          <el-button type="primary" size="large" @click="openChat">
            联系商家
          </el-button>
        </div>
      </div>
    </div>

    <div class="container shop-body">
      <!-- ============ 店内分类（只列该店有商品的分类）============ -->
      <div class="filter-bar">
        <div class="cats">
          <span class="filter-label">店内分类</span>
          <el-radio-group v-model="categoryId" size="default" @change="reload">
            <el-radio-button :value="0">全部</el-radio-button>
            <el-radio-button
              v-for="c in availableCategories"
              :key="c.id"
              :value="c.id"
            >{{ c.name }}（{{ countOf(c.id) }}）</el-radio-button>
          </el-radio-group>
        </div>
        <div class="sorts">
          <el-radio-group v-model="sortBy" size="default" @change="reload">
            <el-radio-button value="sales">销量</el-radio-button>
            <el-radio-button value="priceAsc">价格升</el-radio-button>
            <el-radio-button value="priceDesc">价格降</el-radio-button>
            <el-radio-button value="newest">最新</el-radio-button>
          </el-radio-group>
        </div>
      </div>

      <!-- ============ 商品列表 ============ -->
      <div v-loading="loading">
        <div class="list-head">
          共 {{ total }} 件商品
        </div>
        <div v-if="products.length" class="grid">
          <div
            v-for="p in products"
            :key="p.id"
            class="product-card"
            @click="goDetail(p.id)"
          >
            <img :src="p.mainImage" :alt="p.name" class="cover" loading="lazy" />
            <div class="info">
              <div class="name">{{ p.name }}</div>
              <div class="sub">{{ p.subtitle }}</div>
              <div class="footer">
                <div>
                  <span class="price">
                    <span class="price-symbol">¥</span>
                    <span class="price-value">{{ p.price }}</span>
                  </span>
                  <span v-if="p.originPrice" class="price-origin">
                    ¥{{ p.originPrice }}
                  </span>
                </div>
                <span class="sales">已售 {{ p.sales }}</span>
              </div>
            </div>
          </div>
        </div>
        <el-empty v-else-if="!loading" description="该店铺暂无商品" />
      </div>

      <!-- ============ 分页 ============ -->
      <div v-if="total > pageSize" class="pager">
        <el-pagination
          v-model:current-page="pageNum"
          :page-size="pageSize"
          :total="total"
          layout="prev, pager, next"
          background
          @current-change="reload"
        />
      </div>
    </div>

    <!-- 聊天面板：复用全局那个实例，通过 ref 调它 -->
    <MerchantChat ref="chatRef" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { StarFilled } from '@element-plus/icons-vue'
import { getCategories, getShopInfo, getShopProducts } from '@/api'
import { useUserStore } from '@/stores/user'
import MerchantChat from '@/components/MerchantChat.vue'

/**
 * 店铺主页。
 *
 * 商品列表复用全局的 `/product/list` 接口（传 merchantId 即按店铺筛选），
 * 卡片样式复用 main.css 里的 .grid / .product-card —— 与首页视觉一致。
 */

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()

const merchantId = computed(() => route.params.merchantId)
const shop = ref({})
const products = ref([])
const categories = ref([])
/** 该店每个分类的商品数，用于分类按钮上的角标 */
const catCounts = ref({})
const total = ref(0)
const pageNum = ref(1)
const pageSize = 12
const categoryId = ref(0)
const sortBy = ref('sales')
const loading = ref(false)
const chatRef = ref(null)

const avatarText = computed(() => (shop.value.shopName || '店').slice(0, 1))

/** 只列该店实际有商品的分类，避免出现点了是空的分类 */
const availableCategories = computed(() =>
  categories.value.filter((c) => (catCounts.value[c.id] || 0) > 0)
)

function countOf(id) {
  return catCounts.value[id] || 0
}

async function loadShop() {
  try {
    const res = await getShopInfo(merchantId.value)
    shop.value = res.data || {}
  } catch (e) {
    ElMessage.error('店铺信息加载失败')
  }
}

/**
 * 统计该店各分类的商品数。
 * 一次性把所有分类的商品拉下来（店铺商品数本就不多），
 * 比每点一次分类发一次请求更省。
 */
async function loadCatCounts() {
  try {
    const res = await getShopProducts({ merchantId: merchantId.value, pageSize: 100 })
    const all = res.data?.records || []
    const counts = {}
    all.forEach((p) => {
      const cid = p.categoryId
      counts[cid] = (counts[cid] || 0) + 1
    })
    catCounts.value = counts
  } catch (e) {
    catCounts.value = {}
  }
}

async function loadProducts() {
  loading.value = true
  try {
    const res = await getShopProducts({
      merchantId: merchantId.value,
      categoryId: categoryId.value || undefined,
      sortBy: sortBy.value,
      pageNum: pageNum.value,
      pageSize
    })
    products.value = res.data?.records || []
    total.value = res.data?.total || 0
  } catch (e) {
    products.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function reload() {
  pageNum.value = 1
  loadProducts()
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

async function openChat() {
  if (!userStore.isLogin) {
    ElMessage.warning('登录后可咨询商家')
    router.push({ path: '/login', query: { redirect: route.fullPath } })
    return
  }
  await chatRef.value?.openWithShop(Number(merchantId.value), null)
}

onMounted(async () => {
  await Promise.all([loadShop(), loadCatCounts()])
  await loadProducts()
  try {
    categories.value = (await getCategories()).data || []
  } catch (e) {
    categories.value = []
  }
})

// 切换店铺时重新加载
watch(merchantId, async () => {
  catCounts.value = {}
  categoryId.value = 0
  pageNum.value = 1
  await Promise.all([loadShop(), loadCatCounts()])
  await loadProducts()
})
</script>

<style scoped>
.shop-page {
  min-height: 70vh;
  padding-bottom: 40px;
}

/* ---------- 店铺头 ---------- */
.shop-banner {
  background: linear-gradient(135deg, #e4393c 0%, #ff6b6b 100%);
  padding: 26px 0;
  color: #fff;
}
.banner-inner {
  display: flex;
  align-items: center;
  gap: 18px;
}
.shop-avatar {
  width: 64px;
  height: 64px;
  border-radius: 14px;
  background: rgba(255, 255, 255, 0.22);
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 28px;
  font-weight: 700;
  flex-shrink: 0;
}
.shop-meta {
  flex: 1;
  min-width: 0;
}
.shop-name {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 600;
}
.shop-stats {
  font-size: 13px;
  opacity: 0.95;
  display: flex;
  align-items: center;
}
.shop-desc {
  margin: 6px 0 0;
  font-size: 12px;
  opacity: 0.88;
  line-height: 1.6;
}
.shop-actions {
  flex-shrink: 0;
}

/* ---------- 筛选栏 ---------- */
.filter-bar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 14px;
  flex-wrap: wrap;
  padding: 16px 0 12px;
}
.cats {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}
.filter-label {
  font-size: 13px;
  color: var(--ec-text-light);
}
.sorts {
  flex-shrink: 0;
}

.list-head {
  font-size: 13px;
  color: var(--ec-text-light);
  padding-bottom: 10px;
}

.pager {
  display: flex;
  justify-content: center;
  padding: 24px 0 0;
}

@media (max-width: 800px) {
  .banner-inner {
    flex-wrap: wrap;
  }
  .shop-actions {
    width: 100%;
  }
  .shop-actions :deep(.el-button) {
    width: 100%;
  }
}
</style>