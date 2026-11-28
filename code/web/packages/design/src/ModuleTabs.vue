<script setup lang="ts">
/**
 * 模块页签，对应 12a 的 `UI-P16`：内容区顶部横排当前模块下的页面，
 * 当前项下划线，顺序与侧栏二级一致；模块只有一个页面时不渲染（工作台即如此）。
 *
 * 本组件只负责呈现与「点了哪一项」，跳转由调用方接管（设计包不依赖 router）：
 * - 点当前项不发事件，避免重复跳转把页面内已写进地址栏的筛选与分页冲掉（`UI-X04`）；
 * - 跨模块跳转走侧栏，页签只做模块内切换。
 */
const props = withDefaults(
  defineProps<{
    /** 当前模块下的页面，顺序由调用方按侧栏二级给出。 */
    tabs: Array<{ path: string; title: string }>
    /** 当前页路径，用于标记下划线项。 */
    active: string
  }>(),
  { tabs: () => [] },
)

const emit = defineEmits<{ (e: 'navigate', path: string): void }>()

function activate(path: string) {
  if (path === props.active) {
    return
  }
  emit('navigate', path)
}
</script>

<template>
  <!-- UI-P16：单页模块不渲染页签 -->
  <nav v-if="props.tabs.length > 1" class="module-tabs">
    <button
      v-for="tab in props.tabs"
      :key="tab.path"
      type="button"
      class="module-tab"
      :class="{ 'is-active': tab.path === props.active }"
      :aria-current="tab.path === props.active ? 'page' : undefined"
      @click="activate(tab.path)"
    >
      {{ tab.title }}
    </button>
  </nav>
</template>

<style scoped>
/* UI-P16：横排在内容区顶部，底边一条分隔线，当前项用 2px 下划线压住分隔线 */
.module-tabs {
  display: flex;
  align-items: center;
  gap: 24px;
  margin-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}

/* 页签高 40，文字 14；无底色、无边框，避免与卡片外壳抢视觉 */
.module-tab {
  height: 40px;
  padding: 0;
  border: none;
  border-bottom: 2px solid transparent;
  margin-bottom: -1px;
  background: transparent;
  color: var(--el-text-color-regular);
  font-size: 14px;
  line-height: 1.5;
  cursor: pointer;
}

.module-tab:hover {
  color: var(--el-color-primary);
}

/* UI-P16：当前项下划线，颜色取主色；不改字重，靠线与色区分 */
.module-tab.is-active {
  border-bottom-color: var(--el-color-primary);
  color: var(--el-color-primary);
}
</style>
