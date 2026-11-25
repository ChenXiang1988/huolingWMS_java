<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import ActionButton from './ActionButton.vue'
import StatusTag from './StatusTag.vue'

/**
 * 列表页详情唯一外壳，对应 12 的 `UI-C04`、`UI-D01`~`UI-D13`：
 * - 头部四件套固定：标题、状态标签、副标题 + 唯一一个右上关闭入口（`UI-D06`）。
 * - 宽度三档由调用方按字段列数传入，组件不自行判断档位（`UI-C04`、`UI-D01`）。
 * - 字段列数由 `columns` 显式传入，不由宽度推导（`UI-D04`、`UI-D05`）。
 * - 主体左侧详情导航常驻，分区再少也不隐藏（`UI-D02`）。
 * - 导航与正文双向联动，分区标题即锚点（`UI-D03`）。
 * - 关闭出口三种：右上关闭、Esc、遮罩点击；提交中由 `closable` 一并禁用（`UI-D12`）。
 */

/** 宽度三档：单列 480、两列 720、带导航 880（`UI-C04`）。 */
type DetailDrawerWidth = 480 | 720 | 880

interface DrawerSection {
  title: string
  fields: Array<{ label: string; value: string }>
}

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    subtitle?: string
    status?: string
    statusType?: 'success' | 'info' | 'warning' | 'danger'
    sections: DrawerSection[]
    /** 宽度档位，默认两列 720；带导航或宽字段由调用方升到 880（`UI-C04`）。 */
    width?: DetailDrawerWidth
    /**
     * 字段列数：1 为一行一对（默认），2 为一行两对。
     * 由调用方显式传入，不从 `width` 推导（`UI-D04`、`UI-D05`）。
     */
    columns?: 1 | 2
    /** 关闭出口总开关，默认 true；提交中由页面置 false（`UI-D12`）。 */
    closable?: boolean
  }>(),
  {
    width: 720,
    columns: 1,
    closable: true,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

/**
 * 只有 1 个分区时导航仍渲染，但禁用跳转（`UI-D02`）。
 * 禁用不等于不可聚焦：该导航项保留在 Tab 序列里，用 `aria-disabled` 标记并给出原因。
 */
const canNavigate = computed(() => props.sections.length > 1)

/** 单分区时禁用跳转的原因，随导航项一起呈现，不静默失效（`UI-D02`）。 */
const disabledReason = computed(() => (canNavigate.value ? '' : '仅一个分区'))

/** 宽度只映射成 el-drawer 的 size，不再把 720px 写死在组件里（`UI-C04`）。 */
const drawerSize = computed(() => `${props.width}px`)

/** 正文滚动容器，导航高亮以它为参照系（`UI-D03`）。 */
const scrollRef = ref<HTMLElement | null>(null)
/** 导航容器，上下键切换时按序号移动焦点（`UI-D03`）。 */
const navRef = ref<HTMLElement | null>(null)
/** 当前分区下标，导航高亮与 `aria-current` 都取自它。 */
const activeIndex = ref(0)

/** 分区标题既是导航文案也是正文标题文案，同一份 sections 派生，保证逐字一致（`UI-D03`）。 */
function anchorElements(): HTMLElement[] {
  const root = scrollRef.value
  if (!root) {
    return []
  }
  return Array.from(root.querySelectorAll<HTMLElement>('[data-drawer-anchor]'))
}

/** 正文滚动时跟随当前分区：取已滚过容器顶部的最后一个分区（`UI-D03`）。 */
function syncActiveByScroll(): void {
  const root = scrollRef.value
  const anchors = anchorElements()
  if (!root || anchors.length === 0) {
    return
  }
  // 末尾分区可能短到永远顶不到容器顶部，滚到底时直接归位到最后一个分区
  if (root.scrollTop + root.clientHeight >= root.scrollHeight - 1) {
    activeIndex.value = anchors.length - 1
    return
  }
  const rootTop = root.getBoundingClientRect().top
  let current = 0
  anchors.forEach((anchor, index) => {
    if (anchor.getBoundingClientRect().top - rootTop <= 1) {
      current = index
    }
  })
  activeIndex.value = current
}

/** 减少动效时只做定位不做位移过渡（`UI-T18`）。 */
function scrollBehavior(): ScrollBehavior {
  if (typeof window === 'undefined') {
    return 'auto'
  }
  return window.matchMedia('(prefers-reduced-motion: reduce)').matches ? 'auto' : 'smooth'
}

/** 点击导航项或 Enter 时把分区标题对齐到正文容器顶部并高亮（`UI-D03`）。 */
function scrollToSection(index: number): void {
  if (!canNavigate.value) {
    return
  }
  const root = scrollRef.value
  const anchor = anchorElements()[index]
  if (!root || !anchor) {
    return
  }
  const delta = anchor.getBoundingClientRect().top - root.getBoundingClientRect().top
  root.scrollTo({ top: Math.max(root.scrollTop + delta, 0), behavior: scrollBehavior() })
  activeIndex.value = index
}

/** 上下键只切换高亮与焦点，Enter 沿用按钮原生行为定位（`UI-D03`、`UI-A02`）。 */
function onNavKeydown(event: KeyboardEvent, index: number): void {
  const step = event.key === 'ArrowDown' ? 1 : event.key === 'ArrowUp' ? -1 : 0
  if (step === 0) {
    return
  }
  event.preventDefault()
  const next = Math.min(Math.max(index + step, 0), props.sections.length - 1)
  activeIndex.value = next
  navRef.value?.querySelectorAll<HTMLButtonElement>('.detail-toc-item')[next]?.focus()
}

/** 重开抽屉或切换对象时回到第一个分区，避免残留上一次的滚动位置与高亮。 */
async function resetToFirstSection(): Promise<void> {
  activeIndex.value = 0
  await nextTick()
  scrollRef.value?.scrollTo({ top: 0 })
}

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      void resetToFirstSection()
    }
  },
)

watch(
  () => props.sections,
  () => {
    void resetToFirstSection()
  },
)
</script>

<template>
  <el-drawer
    :model-value="modelValue"
    :size="drawerSize"
    :with-header="false"
    :close-on-click-modal="closable"
    :close-on-press-escape="closable"
    @update:model-value="(value: boolean) => emit('update:modelValue', value)"
  >
    <div class="detail-drawer">
      <div class="detail-header">
        <div class="detail-heading">
          <div class="detail-title">{{ title }}</div>
          <div v-if="subtitle" class="detail-subtitle">{{ subtitle }}</div>
        </div>
        <div class="detail-header-right">
          <StatusTag v-if="status" :label="status" :type="statusType" />
          <ActionButton variant="text" :disabled="!closable" @click="emit('update:modelValue', false)">
            关闭
          </ActionButton>
        </div>
      </div>

      <div class="detail-body">
        <nav ref="navRef" class="detail-toc" aria-label="详情分区">
          <button
            v-for="(section, index) in sections"
            :key="section.title"
            type="button"
            class="detail-toc-item"
            :class="{ active: index === activeIndex, 'is-static': !canNavigate }"
            :aria-disabled="canNavigate ? undefined : 'true'"
            :tabindex="index === activeIndex ? 0 : -1"
            :aria-current="index === activeIndex ? 'location' : undefined"
            @click="scrollToSection(index)"
            @keydown="(event) => onNavKeydown(event, index)"
          >
            <span class="detail-toc-label">{{ section.title }}</span>
            <span v-if="disabledReason" class="detail-toc-reason">{{ disabledReason }}</span>
          </button>
        </nav>

        <div ref="scrollRef" class="detail-fields" @scroll.passive="syncActiveByScroll">
          <section v-for="section in sections" :key="section.title" class="detail-section" data-drawer-anchor>
            <h4>{{ section.title }}</h4>
            <dl class="detail-list" :class="{ 'is-two-columns': columns === 2 }">
              <div v-for="field in section.fields" :key="field.label" class="detail-row">
                <dt>{{ field.label }}</dt>
                <dd>{{ field.value }}</dd>
              </div>
            </dl>
          </section>
        </div>
      </div>

      <div v-if="$slots.footer" class="detail-footer">
        <slot name="footer" />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
/* UI-D02、UI-D03：头部与底部固定，正文分区独立滚动，导航随头部常驻 */
.detail-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.detail-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.detail-heading {
  min-width: 0;
}
.detail-title {
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}
.detail-subtitle {
  margin-top: 4px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.detail-header-right {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-shrink: 0;
}
.detail-body {
  display: flex;
  gap: 24px;
  flex: 1;
  min-height: 0;
  padding: 16px 0;
  overflow: hidden;
}
/* 导航常驻：分区少于 3 个也不隐藏，宽度档位不再由它决定（UI-D02） */
.detail-toc {
  display: flex;
  flex-direction: column;
  gap: 4px;
  width: 140px;
  flex-shrink: 0;
  overflow-y: auto;
}
.detail-toc-item {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  width: 100%;
  padding: 8px 12px;
  border: 0;
  border-radius: 4px;
  background: transparent;
  font-size: 13px;
  line-height: 1.5;
  text-align: left;
  color: var(--el-text-color-regular);
  cursor: pointer;
}
.detail-toc-item:hover:not(.is-static) {
  background: var(--el-fill-color-lighter);
}
.detail-toc-item.active {
  background: var(--el-fill-color-light);
  color: var(--el-text-color-primary);
  font-weight: 500;
}
/*
 * UI-D02：单分区时导航项可聚焦但不可点。用 aria-disabled 而非原生 disabled，
 * 该项留在 Tab 序列里；置灰但焦点描边按 UI-A02 仍可见，且不可点不是静默失效，
 * 原因由紧随其后的 .detail-toc-reason 给出。
 */
.detail-toc-item.is-static {
  color: var(--el-text-color-secondary);
  cursor: default;
}
.detail-toc-item.is-static.active {
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
/* 导航文案超长时换行不横滚，与字段值的换行规则一致（UI-D05） */
.detail-toc-label {
  max-width: 100%;
  overflow-wrap: anywhere;
}
/* 禁用原因跟随导航项文案，字号取 UI-T12 的最小档，不与可点的导航项抢视觉 */
.detail-toc-reason {
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
  font-weight: 400;
}
/* UI-A02：焦点 2px 主色描边，内嵌不引起位移 */
.detail-toc-item:focus-visible {
  outline: 2px solid var(--el-color-primary);
  outline-offset: -2px;
}
.detail-fields {
  flex: 1;
  min-width: 0;
  overflow-y: auto;
}
.detail-section + .detail-section {
  margin-top: 24px;
}
.detail-section h4 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}
/*
 * UI-D04、UI-D05：字段列数取 columns，默认 1 一行一对，传 2 时一行两对。
 * 列数只由调用方传入，不从宽度推导；网格按行优先排布，DOM 顺序即视觉从左到右、从上到下。
 * 标签列宽随列数收窄，给值留出空间；minmax(0, 1fr) 让超长值换行而不是把网格撑横。
 */
.detail-list {
  --detail-label-width: 120px;
  display: grid;
  grid-template-columns: minmax(0, 1fr);
  column-gap: 32px;
  /* 描述列表的上下留白显式写成 4 的倍数（UI-T14），不再依赖浏览器对 dl 的默认 1em */
  margin: 16px 0;
}
.detail-list.is-two-columns {
  --detail-label-width: 88px;
  grid-template-columns: repeat(2, minmax(0, 1fr));
}
/* UI-D05：窄屏两列收成一列，标签列宽回到单列档，避免字段挤压后溢出 */
@media (max-width: 768px) {
  .detail-list.is-two-columns {
    --detail-label-width: 120px;
    grid-template-columns: minmax(0, 1fr);
  }
}
.detail-row {
  display: grid;
  grid-template-columns: var(--detail-label-width) minmax(0, 1fr);
  padding: 8px 0;
  font-size: 14px;
  line-height: 1.5;
}
.detail-row dt {
  color: var(--el-text-color-secondary);
}
/* UI-D05：标签左灰、值右对齐，超长换行不横滚 */
.detail-row dd {
  min-width: 0;
  margin: 0;
  text-align: right;
  overflow-wrap: anywhere;
}
/*
 * UI-C07：主操作最右、次操作在其左，靠 flex-end + DOM 顺序表达，不设 gap。
 * UI-T14：间距单一来源。按钮之间的 12px 由 ActionButton 的相邻兄弟规则
 * （`.action-button + .action-button`）承担，此处再设 gap 会叠加成 24px。
 */
.detail-footer {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
