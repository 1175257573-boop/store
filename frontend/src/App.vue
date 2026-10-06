<template>
  <router-view />
</template>

<script setup>
import { onMounted } from 'vue'
import { useUserStore } from '@/stores/user'

const userStore = useUserStore()

onMounted(() => {
  // 刷新页面后用本地令牌换取最新用户资料
  userStore.fetchProfile()
})
</script>
