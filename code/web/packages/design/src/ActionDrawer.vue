<script setup lang="ts">
import { computed } from 'vue'
import ActionButton from './ActionButton.vue'

/**
 * 非详情类抽屉唯一外壳（新建、编辑、分配、配置），对应 12 的 `UI-C31`：
 * - 头部固定：标题 + 唯一一个右上关闭入口，皮肤与 `DetailDrawer` 同款。
 * - 主体为默认插槽，放表单、勾选列表或左右分栏，页面不在此拼头部、分隔线与操作条。
 * - 底部为具名 `footer` 插槽，右对齐；无 footer 时不渲染空条（`UI-D07`）。
 * - 宽度三档由调用方按内容传入，组件不自行判断档位。
 * - 关闭出口三种：右上关闭、Esc、遮罩点击；提交中由 `closable` 一并禁用（`UI-D12`）。
 */

/** 宽度三档：窄表单 480、勾选列表 720、左右分栏 880（`UI-C31`）。 */
type ActionDrawerWidth = 480 | 720 | 880

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    title: string
    /** 宽度档位，默认 480；勾选列表或左右分栏由调用方升到 720 / 880（`UI-C31`）。 */
    width?: ActionDrawerWidth
    /** 关闭出口总开关，默认 true；提交中由页面置 false（`UI-D12`）。 */
    closable?: boolean
  }>(),
  {
    width: 480,
    closable: true,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
}>()

/** 宽度只映射成 el-drawer 的 size，页面不再自写像素值。 */
const drawerSize = computed(() => `${props.width}px`)
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
    <div class="action-drawer">
      <div class="action-drawer-header">
        <div class="action-drawer-title">{{ title }}</div>
        <ActionButton variant="text" :disabled="!closable" @click="emit('update:modelValue', false)">
          关闭
        </ActionButton>
      </div>

      <div class="action-drawer-body">
        <slot />
      </div>

      <div v-if="$slots.footer" class="action-drawer-footer">
        <slot name="footer" />
      </div>
    </div>
  </el-drawer>
</template>

<style scoped>
/* UI-C31：头部与底部固定，主体独立滚动，外壳自带分隔线与标题字重 */
.action-drawer {
  display: flex;
  flex-direction: column;
  height: 100%;
  min-height: 0;
}
.action-drawer-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  flex-shrink: 0;
  padding-bottom: 16px;
  border-bottom: 1px solid var(--el-border-color-lighter);
}
.action-drawer-title {
  min-width: 0;
  font-size: 16px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}
.action-drawer-body {
  flex: 1;
  min-height: 0;
  padding: 16px 0;
  overflow-y: auto;
}
/*
 * UI-C07：主操作最右、次操作在其左，靠 flex-end + DOM 顺序表达，不设 gap。
 * UI-T14：间距单一来源。按钮之间的 12px 由 ActionButton 的相邻兄弟规则
 * （`.action-button + .action-button`）承担，此处再设 gap 会叠加成 24px。
 */
.action-drawer-footer {
  display: flex;
  justify-content: flex-end;
  flex-shrink: 0;
  padding-top: 16px;
  border-top: 1px solid var(--el-border-color-lighter);
}
</style>
