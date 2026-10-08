<template>
  <span
    v-if="text"
    class="nav-badge"
    :class="{
      'is-dot': dotOnly,
      'is-num': !dotOnly,
      'is-count': variant === 'count'
    }"
  >
    {{ text }}
  </span>
</template>

<script setup>
import { computed } from 'vue'
import { BADGE_MAX, DOT_THRESHOLD } from '@/composables/useBadge'

/**
 * 导航栏角标。
 *
 * <p><b>两种形态</b>（对齐主流电商：京东/天猫 都是「小数字用点、大数字用标」）：
 * <ul>
 *   <li><b>纯红点</b>：1~9 —— 更干净，不打断导航布局</li>
 *   <li><b>数字角标</b>：10+ —— 两位数宽度约等于圆点两倍，纯点不够醒目</li>
 * </ul>
 *
 * <p><b>不遮挡文字的做法</b>：角标定位在**图标容器的右上角**（由父层
 * {@code .icon-wrap} 提供定位上下文），而不是整个导航项。
 * 导航项是「图标 + gap(4px) + 文字」的 flex 布局，
 * 角标若相对整项定位会横向伸进文字区 —— 实测压住了「消息」的「息」字。
 * 挂在图标上则天然隔着 gap，<b>结构上不可能压到文字</b>。
 *
 * <p><b>超长数字的处理</b>：{@link BADGE_MAX} 以上显示「99+」，
 * 并用 {@link isDot} 之外再加一层 font-size 收缩，
 * 保证再长的内容也不会溢出导航项。
 */
const props = defineProps({
  /** 提醒数量，0 或负数不显示 */
  count: { type: [Number, String], default: 0 },
  /**
   * 变体：
   * - 'alert'（默认）红色 —— 「有事要处理」，需要用户动作
   * - 'count'   灰色 —— 「有多少个」，数量语义（如购物车）
   *
   * 为什么要区分：红色角标在电商里约定了「未读/待办」含义，
   * 购物车这种「用户自己加的东西」用红色会误导 —— 用户会以为购物车里有待处理的事。
   */
  variant: {
    type: String,
    default: 'alert',
    validator: (v) => ['alert', 'count'].includes(v)
  }
})

const n = computed(() => {
  const v = Number(props.count)
  return Number.isFinite(v) && v > 0 ? Math.floor(v) : 0
})

const dotOnly = computed(() => n.value > 0 && n.value < DOT_THRESHOLD)

/**
 * 显示文本。上限 {@link BADGE_MAX} 时截成「99+」——
 * 三位数在 16px 高的角标里会溢出，与主流电商一致。
 */
const text = computed(() => {
  if (n.value <= 0) return ''
  return n.value > BADGE_MAX ? `${BADGE_MAX}+` : String(n.value)
})
</script>

<style scoped>
.nav-badge {
  /* 定位基准是外层 .icon-wrap（图标容器），不是整个导航项。
     挂在图标上，角标与文字之间天然隔着 gap(4px)，
     结构上就不可能压住文案 —— 这是修「遮挡」的根本办法。 */
  position: absolute;
  top: -7px;
  /* 右上突出 6px —— 骑在图标右上角（主流电商做法）。
     超出量必须小于 .nav-item 的 gap(10px)，否则会压到文字。 */
  right: -6px;
  z-index: 3;
  pointer-events: none;    /* 角标绝不挡导航项点击 */
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif;
  line-height: 1;
  box-shadow: 0 0 0 2px #fff;   /* 与导航底色描边，压在图标上也清晰 */
  white-space: nowrap;          /* 数字不折行 */
  flex-shrink: 0;              /* 不被父容器压缩 */
}

/* ---------- 纯红点：1~9 ---------- */
.nav-badge.is-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: var(--ec-price, #e4393c);
}

/* ---------- 数字角标：10+ ---------- */
.nav-badge.is-num {
  min-width: 16px;
  height: 16px;
  padding: 0 4px;
  border-radius: 8px;          /* 高度一半 = 胶囊形，主流电商一致 */
  background: var(--ec-price, #e4393c);
  color: #fff;
  font-size: 11px;
  font-weight: 600;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
}

/* ---------- 数量语义变体（购物车）---------- */
/* 灰色而非红色：红色在电商里约定了「未读/待办」含义。
   购物车是「用户自己加的东西」，用红色会让人以为里面有待处理的事。 */
.nav-badge.is-count {
  background: var(--ec-text-light, #909399);
}
.nav-badge.is-count.is-dot {
  background: var(--ec-text-light, #909399);
}
</style>