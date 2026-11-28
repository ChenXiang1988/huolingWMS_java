<script setup lang="ts">
import { useSlots } from 'vue'
import ActionButton from './ActionButton.vue'

/**
 * 筛选区外壳，对应 12 的 `UI-C02`、`UI-C07`：
 * - 一行最多 4 项，标签在左、控件在右；超过 4 项折叠为「展开」；
 * - 折叠状态由调用方写进地址栏（`UI-C02`、`UI-X04`），本组件只发 `update:collapsed`；
 * - 操作条主操作最右，次操作在其左，间距由 ActionButton 的相邻兄弟规则承担。
 */
const props = withDefaults(
  defineProps<{
    /** 折叠态；默认展开，超过 4 项时才出现「展开／收起」入口。 */
    collapsed?: boolean
  }>(),
  { collapsed: false },
)

const emit = defineEmits<{
  (e: 'search'): void
  (e: 'reset'): void
  (e: 'update:collapsed', value: boolean): void
}>()

/** 折叠阈值逐字取 `UI-C02`「一行最多 4 项」。 */
const MAX_VISIBLE_ITEMS = 4

const slots = useSlots()
/** 只数元素节点：纯文本与注释节点不占筛选项位置。页面的筛选项组成在模板里固定，故一次性求值。 */
const itemCount = (slots.default?.() ?? []).filter((vnode) => typeof vnode.type !== 'symbol').length
const collapsible = itemCount > MAX_VISIBLE_ITEMS

function toggle() {
  emit('update:collapsed', !props.collapsed)
}
</script>

<template>
  <div class="filter-bar">
    <div class="filter-fields" :class="{ 'is-collapsed': collapsible && collapsed }">
      <slot />
    </div>
    <div class="filter-actions">
      <!-- UI-C02：超过 4 项时给「展开／收起」，折叠状态由调用方写进地址栏 -->
      <ActionButton v-if="collapsible" variant="text" @click="toggle">
        {{ collapsed ? '展开' : '收起' }}
      </ActionButton>
      <!-- UI-C07：主操作最右，次操作在其左 -->
      <ActionButton variant="secondary" @click="emit('reset')">重置</ActionButton>
      <ActionButton variant="primary" @click="emit('search')">查询</ActionButton>
    </div>
  </div>
</template>

<style scoped>
.filter-bar {
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  padding-bottom: 16px;
  gap: 16px;
}
.filter-fields {
  display: flex;
  flex-wrap: wrap;
  gap: 16px;
}
/*
 * UI-C02：每个筛选项按自身宽度占位，横向排开，不被撑成整行。
 * 插槽内容不继承本组件的作用域，故用 `:deep(> *)` 只作用到直接子项，不进控件内部结构。
 * 控件根容器自带 `width: 100%`（FormInput 的 `.form-input` 就是如此，表单页靠它撑满字段宽度），
 * 到筛选区里这个 100% 会吃满 `.filter-fields`，把后面的控件挤到第二行，故此处压回 `width: auto`，
 * 由调用方写在控件上的宽度（如 `style="width: 200px"`）决定实际宽度。
 * 前缀 `.filter-bar` 用于抬高优先级：`packages/design/src/index.ts` 里 FilterBar 先于 FormInput 导出，
 * 两条同级的 class 选择器相撞时后者胜出，仅写 `.filter-fields :deep(> *)` 压不过控件的 `width: 100%`。
 */
.filter-bar .filter-fields :deep(> *) {
  flex: 0 0 auto;
  width: auto;
}
/* UI-C02：折叠时只保留前 4 项，第 5 项起不渲染；`:deep(> …)` 限定直接子项，不影响控件内部结构 */
.filter-fields.is-collapsed :deep(> :nth-child(n + 5)) {
  display: none;
}
/*
 * UI-C07：操作条内按钮间距取 4 的倍数（UI-T14）。
 * 间距由 ActionButton 自身的相邻规则（`.action-button + .action-button`）承担 12px，
 * 此处不再设 gap，避免与组件内 margin 叠加成 24px。
 */
.filter-actions {
  display: flex;
  align-items: center;
}
</style>
