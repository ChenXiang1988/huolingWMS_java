<script setup lang="ts">
import { computed } from 'vue'
import { formatDate } from './format'

/**
 * 页头外壳，对应 12a 的 `UI-P01` 页头段与 `UI-P14` 工作台页头：
 * 左侧标题（可选当前日期、可选描述），右侧操作插槽右对齐。
 * 内容区顶部由页面自己渲染页头，外壳不代渲染（`UI-P12`）。
 */
const props = withDefaults(
  defineProps<{
    /** 页面标题。 */
    title: string
    /** 一句话描述；不写时不占位。 */
    description?: string
    /** 是否在标题右侧显示当前日期（工作台用，`UI-P14`）。 */
    showDate?: boolean
  }>(),
  { description: '', showDate: false },
)

/** 当前日期：只到天，格式与列表时间同源（`formatDate`），页面不各自拼时间串。 */
const today = computed(() => formatDate(new Date().toISOString()))
</script>

<template>
  <header class="page-header">
    <div class="page-header__main">
      <div class="page-header__title-row">
        <h1 class="page-header__title">{{ props.title }}</h1>
        <span v-if="showDate" class="page-header__date">{{ today }}</span>
      </div>
      <p v-if="description" class="page-header__description">{{ description }}</p>
    </div>
    <div v-if="$slots.actions" class="page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
/* UI-P01：页头左侧标题、右侧主操作；页头下间距 16（UI-T14） */
.page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.page-header__title-row {
  display: flex;
  align-items: baseline;
  gap: 12px;
}

/* UI-T12：标题取 20 档；UI-T13：行高 1.5 */
.page-header__title {
  margin: 0;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.5;
}

/* UI-T12：日期与描述取辅助信息 13 档 */
.page-header__date,
.page-header__description {
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.page-header__description {
  margin: 4px 0 0;
}

.page-header__actions {
  display: flex;
  align-items: center;
  flex-shrink: 0;
}
</style>
