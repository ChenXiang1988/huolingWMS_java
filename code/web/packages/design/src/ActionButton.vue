<script setup lang="ts">
import { computed } from 'vue'

/**
 * 操作按钮外壳，对应 12 的 `UI-C27`、`UI-C29`、`UI-I03`：
 * - 变体只映射到 Element Plus 的 type，颜色全部取 `--el-*` 令牌，组件内不写色值（`UI-C26`）。
 * - 提交中内容淡出、旋转指示由 Element Plus 承担，按钮宽高不变（`UI-C27`）。
 * - 关联弹窗打开时保持按压底色，关闭后恢复，时长按 `UI-T18`（`UI-C29`）。
 * - 前置不满足时置灰并在按钮旁给一句话原因（`UI-I03`）；禁用态不产生悬浮与按下反馈。
 * - 焦点可见沿用 Element Plus 的 `:focus-visible` 描边，组件不写 `outline: none`（`UI-A02`）。
 */
type ActionButtonVariant = 'primary' | 'secondary' | 'danger' | 'text'

const props = withDefaults(
  defineProps<{
    variant?: ActionButtonVariant
    loading?: boolean
    disabled?: boolean
    /** 前置不满足的原因；非空时按钮置灰并在旁边显示这句原因（`UI-I03`）。 */
    reason?: string
    /** 关联弹窗是否打开；打开时保持按压底色（`UI-C29`）。 */
    active?: boolean
    /**
     * 是否铺满所在行。默认 false，保持 `inline-flex` 收缩；
     * 为 true 时按钮占满整行，用于表单底部唯一主操作（`UI-C07`）。
     */
    block?: boolean
  }>(),
  {
    variant: 'secondary',
    loading: false,
    disabled: false,
    reason: '',
    active: false,
    block: false,
  },
)

defineOptions({ inheritAttrs: false })

/**
 * 变体到 Element Plus 的 type 的映射；`secondary` 用默认按钮（空 type）。
 * 文字按钮用 `text` 属性加 primary 表达：Element Plus 2.x 已把 `type="text"` 标为废弃，
 * 且仓库现有文字按钮统一写作 `text type="primary"`，这样视觉与既有页面一致。
 */
const buttonType = computed(() => {
  if (props.variant === 'primary') {
    return 'primary'
  }
  if (props.variant === 'danger') {
    return 'danger'
  }
  if (props.variant === 'text') {
    return 'primary'
  }
  return ''
})

const isText = computed(() => props.variant === 'text')

const hasReason = computed(() => props.reason.trim().length > 0)

/** 给原因即表示前置不满足，与 disabled 等效置灰，不做「点了才报错」（`UI-I03`）。 */
const isDisabled = computed(() => props.disabled || hasReason.value)

/** 禁用与加载中都不保持按压底色，避免两个状态叠加（`UI-C29`）。 */
const isPressHold = computed(() => props.active && !isDisabled.value && !props.loading)
</script>

<template>
  <span class="action-button" :class="{ 'is-block': block }">
    <el-button
      v-bind="$attrs"
      class="action-button__control"
      :class="{
        'is-active': isPressHold,
        'is-press-hold': isPressHold,
        'is-content-fading': loading,
      }"
      :type="buttonType"
      :text="isText"
      :loading="loading"
      :disabled="isDisabled"
    >
      <span class="action-button__label">
        <slot />
      </span>
    </el-button>
    <span v-if="hasReason" class="action-button__reason">{{ reason }}</span>
  </span>
</template>

<style scoped>
.action-button {
  display: inline-flex;
  align-items: center;
  gap: 8px;
}

/*
 * UI-C07：全宽按钮。默认保持 inline-flex 收缩；block 时铺满所在行，
 * 内部 el-button 一并撑满，用于表单底部唯一的主操作。
 * border-box 保证撑满时不外溢父容器。
 */
.action-button.is-block {
  display: flex;
  width: 100%;
  box-sizing: border-box;
}

.action-button.is-block .action-button__control {
  width: 100%;
}

/*
 * UI-C07 + UI-T14：外层 span 取代 el-button 成为兄弟节点后，
 * Element Plus 的 `.el-button + .el-button` 不再命中，成对按钮会贴在一起；
 * 这里补回 12px（4 的倍数）。容器侧（FilterBar 的操作条）不再设 gap，避免叠加成 24px。
 */
.action-button + .action-button {
  margin-left: 12px;
}

/* UI-T18：进入 200ms、退出 150ms，缓动 ease-out */
.action-button .action-button__control {
  transition-duration: 150ms;
  transition-timing-function: ease-out;
}

/*
 * UI-C27：提交中内容淡出；文字仍占位，因此按钮宽高不变。
 * UI-T18 的减少动效只保留透明度：本组件的状态变化只有透明度与配色，不使用位移与缩放。
 */
.action-button__label {
  display: inline-flex;
  align-items: center;
  transition-property: opacity;
  transition-duration: 150ms;
  transition-timing-function: ease-out;
}

.action-button .is-content-fading .action-button__label {
  opacity: 0;
  transition-duration: 200ms;
}

/* 旋转指示居中，不被淡出的文字挤到一侧（Element Plus 的加载图标绝对定位后不参与排版） */
.action-button .is-content-fading :deep(.el-icon.is-loading) {
  position: absolute;
  inset: 0;
  margin: auto;
}

/* UI-C29：弹窗打开期间保持按压底色，关闭后按 UI-T18 恢复 */
.action-button .is-press-hold {
  transition-duration: 200ms;
}

/* 文字按钮的按压底色取 Element Plus 自己的 `.is-text:active` 填充令牌（UI-T21） */
.action-button .is-press-hold.is-text {
  background-color: var(--el-fill-color);
}

/* 禁用态抑制悬浮与按下反馈：阻断状态过渡，配色仍由 Element Plus 的禁用令牌锁定（UI-T25） */
.action-button .action-button__control.is-disabled {
  transition: none;
}

/* UI-I03：禁用原因放在按钮旁，辅助色，字号按 UI-T12 的辅助信息档 */
.action-button__reason {
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
