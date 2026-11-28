<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import ActionButton from './ActionButton.vue'

/**
 * 危险与状态迁移类确认弹窗外壳，对应 12 的 `UI-C05`、`UI-F05`、`UI-W12`：
 * - 内容顺序固定：对象行 → 影响清单 → 原因文本域 → 底部两按钮；
 * - 影响清单逐条成句、不折叠（`UI-W12`：写清对象、数量、后果）；
 * - 主按钮用动作词而不是「确认」，危险动作用危险色、非危险动作按主按钮语义（`UI-T01`、`UI-T04`）；
 * - 提交中右上关闭置灰保留、Esc 与遮罩点击禁用（`UI-D12`），关闭出口仍是三种、不留第四个。
 *
 * 用途是「影响他人且需留原因」的操作（停用、启用这类）；轻量操作用气泡确认即可（`UI-C22`）。
 * 页面只传对象行、影响清单与动作词，不在页面里自写 el-dialog 的头部与字段皮肤。
 */

const props = withDefaults(
  defineProps<{
    modelValue: boolean
    /** 弹窗标题，写成「动作 · 对象」，如「停用 · 货主档案」（`UI-C31` 同一写法）。 */
    title: string
    /** 对象行，写清这次动作落在谁身上，如「货主名称（OW000123）」。 */
    subject?: string
    /** 影响清单：逐条成句、必须逐条显示、不得折叠（`OA-05`）。 */
    impacts: string[]
    /** 原因字段的标签，如「停用原因」。 */
    reasonLabel?: string
    /** 原因长度上限，与契约 1.5 的 `reason` 约束同值（1 至 200 字）。 */
    reasonMaxLength?: number
    /** 主按钮动作词：用「停用」「启用」，不用「确认」「确定」（`UI-W01`、`UI-W12`）。 */
    confirmText: string
    /** 主按钮语义：危险操作用 danger，其余按主按钮（`UI-T01`、`UI-T04`）。 */
    danger?: boolean
    /** 提交中：主按钮行内 spinner，关闭出口禁用（`UI-U05`、`UI-D12`）。 */
    loading?: boolean
  }>(),
  {
    subject: '',
    reasonLabel: '原因',
    reasonMaxLength: 200,
    danger: false,
    loading: false,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: boolean): void
  /** 主按钮：把填写的原因交回页面，由页面发起受理请求。 */
  (e: 'confirm', reason: string): void
}>()

/** 原因文本：每次打开重置，关闭不清空（失败后页面仍要保留原因，`UI-F03`）。 */
const reason = ref('')

/** 必填但为空时主按钮不置灰，点击后就地提示并聚焦（`OA-03` 的必填与置灰分界同口径）。 */
const reasonError = ref('')
const textareaRef = ref<{ focus?: () => void } | null>(null)

const closable = computed(() => !props.loading)

watch(
  () => props.modelValue,
  (visible) => {
    if (visible) {
      reasonError.value = ''
    }
  },
)

/** 原因由页面在失败重提时回填（`OA-05`「原因文本保留在受理条的重提面板」）。 */
function setReason(value: string): void {
  reason.value = value
}

function close(): void {
  if (!closable.value) {
    return
  }
  emit('update:modelValue', false)
}

function submit(): void {
  if (props.loading) {
    return
  }
  if (!reason.value.trim()) {
    reasonError.value = '必填'
    textareaRef.value?.focus?.()
    return
  }
  reasonError.value = ''
  emit('confirm', reason.value.trim())
}

defineExpose({ setReason })
</script>

<template>
  <el-dialog
    :model-value="modelValue"
    :title="title"
    width="480px"
    :close-on-click-modal="closable"
    :close-on-press-escape="closable"
    :show-close="closable"
    @update:model-value="(value: boolean) => { if (!value) close() }"
  >
    <div class="impact-dialog">
      <!-- UI-W12：对象行写清这次动作落在谁身上 -->
      <p v-if="subject" class="impact-dialog__subject">{{ subject }}</p>
      <!-- OA-05：影响清单四条逐条成句，不得折叠 -->
      <ul v-if="impacts.length" class="impact-dialog__impacts">
        <li v-for="line in impacts" :key="line">{{ line }}</li>
      </ul>
      <!-- UI-C03：原因必填，错误在控件下方 4px，字数提示与 maxlength 同步 -->
      <div class="impact-dialog__reason">
        <label class="impact-dialog__label" for="impact-dialog-reason">{{ reasonLabel }}</label>
        <el-input
          id="impact-dialog-reason"
          ref="textareaRef"
          v-model="reason"
          type="textarea"
          :rows="3"
          :maxlength="reasonMaxLength"
          :disabled="loading"
          show-word-limit
          :class="{ 'is-error': reasonError }"
        />
        <!-- 字数提示由 el-input 的 show-word-limit 承担（「0/200」），错误只占 4px 的下方位置 -->
        <p v-if="reasonError" class="impact-dialog__error">{{ reasonError }}</p>
      </div>
    </div>

    <template #footer>
      <!-- UI-C07：主操作最右，次操作在其左；按钮间距由 ActionButton 的相邻兄弟规则承担 -->
      <div class="impact-dialog__footer">
        <ActionButton variant="secondary" :disabled="loading" @click="close">取消</ActionButton>
        <ActionButton
          :variant="danger ? 'danger' : 'primary'"
          :loading="loading"
          :disabled="loading"
          @click="submit"
        >
          {{ confirmText }}
        </ActionButton>
      </div>
    </template>
  </el-dialog>
</template>

<style scoped>
.impact-dialog {
  display: flex;
  flex-direction: column;
}
/* UI-T12：对象行取正文档，字重 600 强调对象，与影响清单区分 */
.impact-dialog__subject {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}
/* UI-W12：影响清单逐条列出，圆点前置，行距按 UI-T13 */
.impact-dialog__impacts {
  margin: 0 0 16px;
  padding-left: 20px;
  font-size: 14px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}
.impact-dialog__impacts li + li {
  margin-top: 4px;
}
.impact-dialog__reason {
  display: flex;
  flex-direction: column;
}
/* UI-C03：标签在控件上方，字重 500，左对齐（弹窗内单列，不做右对齐的窄标签列） */
.impact-dialog__label {
  margin-bottom: 4px;
  font-size: 14px;
  font-weight: 500;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}
/* UI-C03：错误文案在控件下方 4px、危险色；无错误时给字数提示（辅助色） */
.impact-dialog__error {
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-danger);
}
/* UI-C07：主操作最右，次操作在其左，不设 gap（间距由 ActionButton 承担） */
.impact-dialog__footer {
  display: flex;
  justify-content: flex-end;
  align-items: center;
}
</style>
