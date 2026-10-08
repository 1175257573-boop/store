<template>
  <div class="home container">
    <!-- 轮播横幅：用纯 CSS 渐变模拟，避免依赖外部图片资源 -->
    <el-carousel height="280px" class="banner" :interval="5000">
      <el-carousel-item v-for="b in banners" :key="b.title">
        <div class="banner-item" :style="{ background: b.bg }">
          <div class="banner-content">
            <h2>{{ b.title }}</h2>
            <p>{{ b.desc }}</p>
            <el-button type="danger" @click="goCategory(b.categoryId)">
              立即选购
            </el-button>
          </div>
        </div>
      </el-carousel-item>
    </el-carousel>

    <!-- 分类导航 -->
    <div class="card-box category-box">
      <div
        v-for="c in categories"
        :key="c.id"
        class="category-item"
        :class="{ active: query.categoryId === c.id }"
        @click="goCategory(c.id)"
      >
        <span class="icon">{{ c.icon }}</span>
        <span class="name">{{ c.name }}</span>
      </div>
    </div>

    <!-- 排序栏 -->
    <div class="toolbar">
      <div class="result-info">
        共 <b>{{ total }}</b> 件商品
        <template v-if="query.keyword">，关键字「{{ query.keyword }}」</template>
      </div>
      <el-radio-group v-model="sortBy" size="small" @change="onSortChange">
        <el-radio-button value="default">综合</el-radio-button>
        <el-radio-button value="sales">销量</el-radio-button>
        <el-radio-button value="priceAsc">价格升</el-radio-button>
        <el-radio-button value="priceDesc">价格降</el-radio-button>
        <el-radio-button value="newest">最新</el-radio-button>
      </el-radio-group>
    </div>

    <!-- 商品列表 -->
    <div v-loading="loading" class="list-area">
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
            <div v-if="p.shopName" class="shop-tag" @click.stop="goShop(p.merchantId)">
              <el-icon><Shop /></el-icon>{{ p.shopName }}
            </div>
            <div class="footer">
              <div>
                <span class="price">
                  <span class="price-symbol">¥</span>
                  <span class="price-value">{{ p.price }}</span>
                </span>
                <span v-if="p.originPrice" class="price-origin">¥{{ p.originPrice }}</span>
              </div>
              <span class="sales">已售 {{ p.sales }}</span>
            </div>
          </div>
        </div>
      </div>

      <el-empty
        v-else-if="!loading"
        :description="query.keyword ? '没有找到相关商品，换个关键字试试' : '暂无商品'"
      />
    </div>

    <!-- 分页 -->
    <div v-if="total > pageSize" class="pagination">
      <el-pagination
        v-model:current-page="query.pageNum"
        :page-size="pageSize"
        :total="total"
        layout="prev, pager, next, jumper, total"
        background
        @current-change="loadProducts"
      />
    </div>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { getProductList, getCategories } from '@/api'
import { Shop } from '@element-plus/icons-vue'

defineOptions({ name: 'ProductList' })

const route = useRoute()
const router = useRouter()

const pageSize = 12
const loading = ref(false)
const products = ref([])
const total = ref(0)
const categories = ref([])
const sortBy = ref('default')

// 与 URL query 双向同步：分享链接能还原筛选状态
const query = reactive({
  pageNum: Number(route.query.pageNum) || 1,
  categoryId: route.query.categoryId ? Number(route.query.categoryId) : null,
  keyword: route.query.keyword || ''
})

const banners = [
  {
    title: '旗舰机型上新',
    desc: 'iPhone 16 Pro / Mate 70 Pro 现货发售',
    bg: 'linear-gradient(120deg, #ff6b6b 0%, #e4393c 100%)',
    categoryId: 1
  },
  {
    title: '办公效率升级',
    desc: '笔记本 / 显示器 开学季特惠',
    bg: 'linear-gradient(120deg, #4facfe 0%, #2b6cb0 100%)',
    categoryId: 2
  },
  {
    title: '品质生活之选',
    desc: '家电以旧换新，最高补贴 500 元',
    bg: 'linear-gradient(120deg, #43e97b 0%, #2f9e6e 100%)',
    categoryId: 3
  }
]

async function loadProducts() {
  loading.value = true
  try {
    const res = await getProductList({
      pageNum: query.pageNum,
      pageSize,
      categoryId: query.categoryId || undefined,
      keyword: query.keyword || undefined,
      sortBy: sortBy.value
    })
    products.value = res.data.records || []
    total.value = Number(res.data.total) || 0
  } catch (e) {
    products.value = []
    total.value = 0
  } finally {
    loading.value = false
  }
}

function loadCategories() {
  getCategories()
    .then(res => (categories.value = res.data || []))
    .catch(() => (categories.value = []))
}

/** 更新 URL，保持筛选状态可分享/刷新不丢 */
function syncQuery() {
  const q = {}
  if (query.pageNum > 1) q.pageNum = query.pageNum
  if (query.categoryId) q.categoryId = query.categoryId
  if (query.keyword) q.keyword = query.keyword
  router.replace({ path: '/', query: q })
}

function goCategory(id) {
  query.categoryId = query.categoryId === id ? null : id
  query.pageNum = 1
  syncQuery()
  loadProducts()
}

function goShop(merchantId) {
  if (merchantId) router.push(`/shop/${merchantId}`)
}

function goDetail(id) {
  router.push(`/product/${id}`)
}

function onSortChange() {
  query.pageNum = 1
  syncQuery()
  loadProducts()
}

// 顶部搜索栏跳转过来时，keyword 变化需重新拉数据
watch(
  () => route.query,
  newQ => {
    const newKeyword = newQ.keyword || ''
    const newCat = newQ.categoryId ? Number(newQ.categoryId) : null
    if (newKeyword !== query.keyword || newCat !== query.categoryId) {
      query.keyword = newKeyword
      query.categoryId = newCat
      query.pageNum = 1
      loadProducts()
    }
  }
)

onMounted(() => {
  loadCategories()
  loadProducts()
})
</script>

<style scoped>
.banner {
  margin: 20px 0 16px;
  border-radius: var(--ec-radius);
  overflow: hidden;
}
.banner-item {
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  color: #fff;
}
.banner-content {
  text-align: center;
}
.banner-content h2 {
  font-size: 32px;
  margin: 0 0 12px;
  letter-spacing: 2px;
}
.banner-content p {
  font-size: 15px;
  margin: 0 0 20px;
  opacity: 0.92;
}

.category-box {
  display: flex;
  gap: 8px;
  padding: 16px 12px;
  flex-wrap: wrap;
}
.category-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  padding: 10px 20px;
  border-radius: 8px;
  cursor: pointer;
  transition: background 0.2s;
  min-width: 88px;
}
.category-item:hover {
  background: #fdf0f0;
}
.category-item.active {
  background: var(--ec-primary);
  color: #fff;
}
.category-item .icon {
  font-size: 22px;
}
.category-item .name {
  font-size: 13px;
}

.toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin: 18px 0 14px;
  flex-wrap: wrap;
  gap: 10px;
}
.result-info {
  font-size: 14px;
  color: var(--ec-text-light);
}
.result-info b {
  color: var(--ec-primary);
}

.list-area {
  min-height: 320px;
}

.pagination {
  display: flex;
  justify-content: center;
  margin-top: 28px;
}
</style>
