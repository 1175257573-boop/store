<template>
  <span v-if="count > 0" class="nav-badge" :class="dotOnly ? 'is-dot' : 'is-num'">
    {{ dotOnly ? '' : display }}
  </span>
</template>

<script setup>
import { computed } from 'vue'

/**
 * 导航栏红点。
 *
 * <p>两种形态（需求要求）：
 * <ul>
 *   <li><b>纯红点</b>：数量 1~9 时用点，更干净、不打断导航布局</li>
 *   <li><b>数字角标</b>：数量 ≥10 时角标会变宽，纯点反而不够醒目，此时显示数字</li>
 * </ul>
 *
 * <p>为什么 10 是分界：两位数角标的宽度约等于一个小圆点的两倍，
 * 再大就开始挤占相邻导航项。99+ 已经用 max 截断。
 */
const props = defineProps({
  count: { type: [Number, String], default: 0 },
  /** 强制纯点（用于「有待办但不显示具体数量」的场景） */
  alwaysDot: { type: Boolean, default: false }
})

const n = computed(() => {
  const v = Number(props.count)
  return Number.isFinite(v) && v > 0 ? Math.floor(v) : 0
})

const dotOnly = computed(() => props.alwaysDot || n.value < 10)

const display = computed(() => (n.value > 99 ? '99+' : String(n.value)))
</script>

<style scoped>
.nav-badge {
  position: absolute;
  /* 定位基准是整个导航项（图标 + 文字），不是图标本身。
     用 `right` 会把红点推到文字上方；这里改用 `left` 贴着图标右缘，
     才能落在「图标右上角」这个符合直觉的位置。
     偏移量 = 图标宽度(1em ≈ 14px) + 3px，让红点骑在图标右缘。 */
  top: 4px;
  left: 13px;
  z-index: 2;
  pointer-events: none;   /* 红点不该挡住导航项的点击 */
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  line-height: 1;
  box-shadow: 0 0 0 2px #fff;   /* 与导航底色描边，压在图标上也能看清 */
}

/* 纯红点 */
.nav-badge.is-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ec-price, #e4393c);
}

/* 数字角标 */
.nav-badge.is-num {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;
  background: var(--ec-price, #e4393c);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

/* 导航项 hover/激活时底色变化，红点描边跟着变，否则像贴在白底上 */
.nav-badge.deep {
  box-shadow: 0 0 0 2px #fdf0f0;
}
</style>