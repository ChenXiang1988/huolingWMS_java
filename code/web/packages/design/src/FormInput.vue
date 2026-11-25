<script setup lang="ts">
import { computed } from 'vue'

/**
 * 单行输入外壳，对应 12 的 `UI-C28`、`UI-C03`，配色取 `UI-T25`：
 * - 边界用内描边表示，悬浮只加深描边，不移动边框、不改变控件尺寸（`UI-C28`）。
 * - 键盘焦点 2px 主色内描边，不用 3px 外发光环（`UI-C28` + `UI-A02`）。
 * - 错误态按 `UI-T25`：文字与边框危险色、底色取危险色浅色阶；错误态优先于悬浮与焦点。
 * - 错误与帮助文案在控件下方 4px，错误用危险色、帮助用辅助色（`UI-C03`）。
 * - 不写死高度：高度由 Element Plus 的 size 档位与调用方决定（`UI-C10` 单行才用本组件）。
 */
const model = defineModel<string | number>()

const props = withDefaults(
  defineProps<{
    /** 字段级错误文案，只说规则（`UI-W09`），显示在控件下方 4px。 */
    error?: string
    /** 帮助文案，显示在控件下方 4px；有错误时不显示。 */
    help?: string
  }>(),
  {
    error: '',
    help: '',
  },
)

defineOptions({ inheritAttrs: false })

const hasError = computed(() => props.error.trim().length > 0)
const hasHelp = computed(() => props.help.trim().length > 0)
</script>

<template>
  <div class="form-input" :class="{ 'is-error': hasError }">
    <el-input v-model="model" v-bind="$attrs" />
    <p v-if="hasError" class="form-input__message is-error">{{ error }}</p>
    <p v-else-if="hasHelp" class="form-input__message">{{ help }}</p>
  </div>
</template>

<style scoped>
.form-input {
  display: flex;
  flex-direction: column;
  /* UI-C03：控件在 el-form-item__content 等 flex 容器里撑满整行，不收缩为内容宽 */
  width: 100%;
  /* 撑满时不允许外溢到父容器 */
  box-sizing: border-box;
  min-width: 0;
}

/* UI-C28：边界用内描边，悬浮只加深描边，不移动边框、不改变控件尺寸 */
.form-input :deep(.el-input__wrapper) {
  box-shadow: 0 0 0 1px var(--el-border-color) inset;
  transition-duration: 150ms;
  transition-timing-function: ease-out;
}

.form-input:not(.is-error)
  :deep(.el-input:not(.is-disabled):not(:focus-within) .el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px var(--el-border-color-dark) inset;
}

/* UI-C28 + UI-A02：键盘焦点 2px 主色描边；进入 200ms（UI-T18） */
.form-input:not(.is-error) :deep(.el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 2px var(--el-color-primary) inset;
  transition-duration: 200ms;
}

/* UI-T25 错误态：文字、底色、边框三件套，悬浮与焦点都不覆盖它 */
.form-input.is-error :deep(.el-input__wrapper) {
  background-color: var(--el-color-danger-light-9);
  box-shadow: 0 0 0 1px var(--el-color-danger) inset;
}

.form-input.is-error :deep(.el-input__wrapper:focus-within) {
  box-shadow: 0 0 0 2px var(--el-color-danger) inset;
  transition-duration: 200ms;
}

.form-input.is-error :deep(.el-input__inner) {
  color: var(--el-color-danger);
}

/* UI-C03：错误与帮助文案在控件下方 4px，字号取 UI-T12 最小档，行高按 UI-T13 */
.form-input__message {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.form-input__message.is-error {
  color: var(--el-color-danger);
}
</style>
