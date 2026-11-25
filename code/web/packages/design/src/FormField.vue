<script setup lang="ts">
import { computed } from 'vue'

/**
 * 表单项外壳，对应 12 的 `UI-C03`：
 * - 标签右对齐，宽度 100（四字标签）或 120（六字标签），字重按 `UI-T24` 取 500。
 * - 必填星号前置：沿用 Element Plus 的 `is-required` + `asterisk-left`，不改写内部结构。
 * - 错误文案在控件下方 4px、危险色，帮助文案同位置、辅助色；有错误时只显示错误。
 * - 错误与帮助都只做呈现，字段级规则以服务端与调用方为准（`UI-W09`、`UI-I11`）。
 */
const props = withDefaults(
  defineProps<{
    label: string
    required?: boolean
    /** 字段级错误文案，显示在控件下方 4px（`UI-C03`）。 */
    error?: string
    /** 帮助文案，显示在控件下方 4px；有错误时不显示（`UI-C03`）。 */
    help?: string
    /** 标签宽度：四字标签 100，六字标签 120（`UI-C03`）。 */
    labelWidth?: 100 | 120
  }>(),
  {
    error: '',
    help: '',
    labelWidth: 100,
  },
)

defineOptions({ inheritAttrs: false })

const hasError = computed(() => (props.error ?? '').trim().length > 0)
const hasHelp = computed(() => (props.help ?? '').trim().length > 0)
</script>

<template>
  <el-form-item
    v-bind="$attrs"
    class="form-field"
    label-position="right"
    :label="label"
    :label-width="labelWidth"
    :required="required"
    :error="error || undefined"
  >
    <slot />
    <p v-if="!hasError && hasHelp" class="form-field__help">{{ help }}</p>
  </el-form-item>
</template>

<style scoped>
/* UI-C03：标签字重取 UI-T24 的 500，右对齐由 label-position 交给 Element Plus */
.form-field :deep(.el-form-item__label) {
  font-weight: 500;
}

/* UI-C03：错误文案在控件下方 4px（Element Plus 默认 2px） */
.form-field :deep(.el-form-item__error) {
  padding-top: 4px;
}

/* UI-C03：帮助文案独占一行，位于控件下方 4px，辅助色，字号与错误文案同档 */
.form-field__help {
  flex: 0 0 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
