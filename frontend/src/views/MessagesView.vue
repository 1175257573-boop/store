<template>
  <div class="messages-page container">
    <div class="page-head">
      <h2>{{ isMerchant ? '买家咨询' : '我的咨询' }}</h2>
      <p class="sub">
        {{ isMerchant
          ? '买家就商品发起的咨询，在商家中心也能看到记录'
          : '与商家的对话会保留在这里，商品详情页也能重新发起' }}
      </p>
    </div>

    <div class="card-box">
      <!-- 复用同一个聊天组件的内嵌形态 -->
      <div class="embed-wrap">
        <MerchantChat :embedded="true" :trigger="false" />
      </div>
    </div>
  </div>
</template>

<script setup>
import MerchantChat from '@/components/MerchantChat.vue'
import { useUserStore } from '@/stores/user'

/**
 * 「我的消息」页面。
 *
 * 复用了详情页同一个 MerchantChat 组件 ——
 * 会话列表与消息区的逻辑只写一份，改一处两处生效。
 */
const userStore = useUserStore()
const isMerchant = userStore.isMerchant
</script>

<style scoped>
.messages-page {
  padding: 20px 16px 40px;
}
.page-head {
  margin-bottom: 16px;
}
.page-head h2 {
  margin: 0 0 6px;
  font-size: 20px;
  color: var(--ec-text);
}
.sub {
  margin: 0;
  font-size: 13px;
  color: var(--ec-text-light);
}
.card-box {
  background: #fff;
  border-radius: var(--ec-radius);
  overflow: hidden;
}
.embed-wrap {
  height: calc(100vh - 240px);
  min-height: 460px;
}
</style>