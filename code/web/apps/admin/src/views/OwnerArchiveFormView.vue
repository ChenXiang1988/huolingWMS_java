<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  AcceptPanel,
  ActionButton,
  FormField,
  FormInput,
  FormSection,
  PageHeader,
} from '@wms/design'
import { ownerFieldLabels } from '@wms/dict'
import {
  ApiError,
  describeApiError,
  newIdempotencyKey,
  type OwnerArchiveCreateBody,
  type OwnerArchiveDetail,
  type OwnerStatus,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import {
  describeOwnerAcceptFailure,
  ownerResultSummary,
  toOwnerAcceptancePoll,
  useHubOwnerArchiveApi,
} from '../api/owner-archive'

/**
 * 货主档案的新建与编辑（`UI-P03` 骨架、`ui.md`「OA-03」「OA-04」「OA-10」）。
 *
 * 两个路由共用同一份字段装配与校验规则，差异只在标题、副标题与提交接口：
 * `/hub/owner-archives/new` 走 API-023，`/hub/owner-archives/:ownerNo/edit` 先 API-022 回填再走 API-024。
 *
 * 只做字段级校验（必填、长度、字符集），判重与状态守卫一律以服务端返回为准（`UI-I11`）：
 * 信用代码是否重复只在受理结果的 `RULE_DUPLICATE_CREDIT_CODE` 出现，名称允许重名、不做本地查重。
 * 写入只拿 202 受理票据（D20），结果由 API-027 轮询（首发 1 秒、此后 3 秒、最长 60 秒）。
 */
const LIST_PATH = '/hub/owner-archives'
const auth = useAuthStore()
const api = useHubOwnerArchiveApi()
const route = useRoute()
const router = useRouter()
const pollAcceptance = toOwnerAcceptancePoll(api)

const ownerNo = computed(() => (typeof route.params.ownerNo === 'string' ? route.params.ownerNo : ''))
const isEdit = computed(() => ownerNo.value !== '')

/** 返回来源：表单页 query 带列表当时的完整路径（`design.md`「刷新与回退后的恢复行为」）。 */
const from = computed(() => {
  const value = route.query.from
  return typeof value === 'string' && value.startsWith(LIST_PATH) ? value : LIST_PATH
})

const form = reactive({ ownerName: '', contactName: '', contactPhone: '', creditCode: '' })
const errors = reactive<Record<string, string>>({})
/** 编辑场景的乐观锁版本号：每次进入表单重新取，不缓存（`OA-04`）。 */
const version = ref<number | null>(null)
/** 编辑场景的当前状态：停用态不可改档，提交置灰并给原因（`OA-04`）。 */
const currentStatus = ref<OwnerStatus | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const submitting = ref(false)
/** 受理中：表单转只读（`OA-03`「表单转只读并进入受理中」）。 */
const locked = ref(false)
const acceptNo = ref<string | null>(null)
const replayed = ref(false)
const topError = ref('')
/** 版本冲突面板与刷新后的提示（`UI-X12`、`OA-10`）。 */
const conflict = ref(false)
const refreshNotice = ref('')
/** 刷新查看后被服务端值改过的字段：警告色 + 文字双编码标记（`UI-T03`）。 */
const changedFields = reactive<Record<string, boolean>>({})
/** 受理成功后的货主编号，用于「查看详情」定位（`OA-03`）。 */
const succeededNo = ref('')
/** 同一表单重提沿用首次的幂等键；输入一变就清空，重提换新键（`OA-10`）。 */
const submitKey = ref('')
let lastSubmittedKey = ''

const canWrite = computed(() => auth.hasPermission(PERMISSIONS.ownerHubWrite))
const title = computed(() => (isEdit.value ? '编辑货主档案' : '新建货主档案'))
const subtitle = computed(() => (isEdit.value && ownerNo.value ? `货主编号 ${ownerNo.value}` : ''))

/** `OA-03` 的帮助文案，逐字取 `ui.md`「1.4 帮助文案」。 */
const helps = {
  ownerName: '允许重名，同名以货主编号区分',
  contactPhone: '6 至 32 位，可含空格与 + - ( )',
  creditCode: '统一社会信用代码，固定 18 位大写字母与数字，不含字母 I、O、S、V、Z',
}

/** 只校验长度与字符集，不校验校验位；大小写不静默转写（契约「信用代码校验深度」）。 */
const creditCodePattern = /^[0-9A-HJ-NP-RT-UW-Y]{18}$/
const phonePattern = /^[0-9+()\-\s]{6,32}$/

const submitReason = computed(() => {
  if (!canWrite.value) {
    return '当前账号没有货主档案的写权限'
  }
  if (detailLoading.value) {
    return '详情尚未加载完成'
  }
  if (currentStatus.value === 'DISABLED') {
    return '停用状态不可改档，需先启用'
  }
  if (locked.value) {
    return '受理中，请等待结果'
  }
  return ''
})

function clearErrors() {
  Object.keys(errors).forEach((key) => delete errors[key])
}

/** 失焦与提交共用同一套字段级规则，错误文案只说规则（`UI-W09`）。 */
function validateField(key: 'ownerName' | 'contactName' | 'contactPhone' | 'creditCode'): string {
  const value = form[key].trim()
  if (!value) {
    return '必填'
  }
  if (key === 'ownerName' && value.length > 128) {
    return '最多 128 个字'
  }
  if (key === 'contactName' && value.length > 64) {
    return '最多 64 个字'
  }
  if (key === 'contactPhone' && !phonePattern.test(value)) {
    return '6 至 32 位，只能包含数字、空格与 + - ( )'
  }
  if (key === 'creditCode' && !creditCodePattern.test(value)) {
    return '需为 18 位大写字母与数字'
  }
  return ''
}

function onBlur(key: 'ownerName' | 'contactName' | 'contactPhone' | 'creditCode') {
  const message = validateField(key)
  if (message) {
    errors[key] = message
  } else {
    delete errors[key]
  }
}

/**
 * 输入变化即清空幂等键：请求体已变，沿用原键会命中同键异体（`OA-10`「输入变化即清空键」）。
 */
function onInput(key: string) {
  submitKey.value = ''
  if (key in changedFields) {
    delete changedFields[key]
  }
  if (errors[key]) {
    delete errors[key]
  }
}

function validate(): boolean {
  clearErrors()
  let firstInvalid = ''
  ;(['ownerName', 'contactName', 'contactPhone', 'creditCode'] as const).forEach((key) => {
    const message = validateField(key)
    if (message) {
      errors[key] = message
      if (!firstInvalid) {
        firstInvalid = key
      }
    }
  })
  if (firstInvalid) {
    // 缺失不置灰提交按钮，点提交后就地提示并聚焦第一个错误字段（`OA-03`）
    document.querySelector<HTMLElement>(`[data-field="${firstInvalid}"] input`)?.focus()
  }
  return !firstInvalid
}

/** 同步 `PARAM_INVALID` 按 `details[].field` 就地定位；映射不到控件时落表单顶部错误块（`OA-10`）。 */
function applyFieldErrors(e: unknown) {
  if (!(e instanceof ApiError)) {
    return
  }
  const known = ['ownerName', 'contactName', 'contactPhone', 'creditCode']
  const unmapped: string[] = []
  ;(e.details ?? []).forEach((detail) => {
    if (known.includes(detail.field)) {
      errors[detail.field] = detail.reason
    } else {
      unmapped.push(`${detail.field}：${detail.reason}`)
    }
  })
  if (unmapped.length) {
    topError.value = unmapped.join('；')
  }
}

function applyDetail(data: OwnerArchiveDetail) {
  form.ownerName = data.ownerName
  form.contactName = data.contactName
  form.contactPhone = data.contactPhone
  form.creditCode = data.creditCode
  // 版本号每次进入表单重新取，缓存旧版本会让恢复后的第一次提交必然撞冲突（`OA-04`）
  version.value = data.version
  currentStatus.value = data.status
}

/**
 * 冲突后的「刷新查看」（`UI-X12`、`OA-10`）：
 * 只更新版本号与状态，**不覆盖用户已填内容**；服务端值与当前输入不一致的字段打「已变更」标记。
 */
function mergeLatest(data: OwnerArchiveDetail) {
  const fields = ['ownerName', 'contactName', 'contactPhone', 'creditCode'] as const
  fields.forEach((key) => {
    if (form[key] !== data[key]) {
      changedFields[key] = true
    }
  })
  version.value = data.version
  currentStatus.value = data.status
  refreshNotice.value = `已载入最新数据（版本 ${data.version}），请核对后重新提交`
  conflict.value = false
  submitKey.value = ''
}

async function loadDetail(merge: boolean) {
  detailLoading.value = true
  detailError.value = ''
  try {
    const data = await api.detail(ownerNo.value)
    if (merge) {
      mergeLatest(data)
    } else {
      applyDetail(data)
    }
  } catch (e) {
    detailError.value = describeApiError(e)
  } finally {
    detailLoading.value = false
  }
}

async function submit() {
  if (submitting.value || locked.value) {
    return
  }
  topError.value = ''
  if (!canWrite.value || currentStatus.value === 'DISABLED') {
    return
  }
  if (!validate()) {
    return
  }
  if (!isEdit.value && !submitKey.value) {
    submitKey.value = newIdempotencyKey()
  }
  // 编辑页必须带进入表单时取到的版本号；版本缺失表示详情未加载完成，不提交
  if (isEdit.value && version.value === null) {
    topError.value = '详情尚未加载完成，请重试后再提交'
    return
  }
  submitting.value = true
  try {
    const body: OwnerArchiveCreateBody = {
      ownerName: form.ownerName.trim(),
      contactName: form.contactName.trim(),
      contactPhone: form.contactPhone.trim(),
      creditCode: form.creditCode.trim(),
    }
    if (!submitKey.value) {
      submitKey.value = newIdempotencyKey()
    }
    lastSubmittedKey = submitKey.value
    const ticket = isEdit.value
      ? await api.update(ownerNo.value, { ...body, version: version.value as number }, submitKey.value)
      : await api.create(body, submitKey.value)
    acceptNo.value = ticket.acceptNo
    replayed.value = ticket.replayed === true
    // 表单转只读并进入受理中，不显示成功（`UI-F01`）
    locked.value = true
  } catch (e) {
    clearErrors()
    applyFieldErrors(e)
    if (!topError.value) {
      topError.value = describeApiError(e)
    }
  } finally {
    submitting.value = false
  }
}

/** 受理终结：成功按 `UI-F04` 给 2 秒轻提示；失败恢复可编辑并保留输入（`UI-F03`）。 */
function onSettled(status: string, result: { ownerNo?: string | null } | null, errorCode: string | null) {
  if (status === 'SUCCEEDED') {
    succeededNo.value = result?.ownerNo ?? ownerNo.value
    const number = succeededNo.value
    ElMessage({
      message: isEdit.value ? `货主档案已更新，货主编号 ${number}` : `货主档案已建档，货主编号 ${number}`,
      type: 'success',
      duration: 2000,
    })
    return
  }
  locked.value = false
  // 版本冲突就地给面板与两出口（`UI-X12`）；其余失败由受理条的三段文案与出口承担（`OA-10`）
  conflict.value = errorCode === 'CONFLICT_VERSION'
}

/** 受理条的「刷新」出口：重取详情，不顺带清筛选（本页没有列表）。 */
function onRefresh() {
  if (isEdit.value) {
    void loadDetail(false)
  }
}

/** 受理失败的出口分派（`OA-10`）：出口编码来自单处分派表，页面只做动作。 */
function onAcceptExit(code: string) {
  switch (code) {
    case 'refresh':
      // 「刷新查看」：重取最新详情与版本号，保留用户输入并标出被改过的字段（`UI-X12`）
      if (isEdit.value) {
        void loadDetail(true)
      }
      break
    case 'discard':
      goBack()
      break
    case 'editInput':
      locked.value = false
      break
    case 'filterByCreditCode':
      void router.push({ path: LIST_PATH, query: { creditCode: form.creditCode.trim(), detail: '' } })
      break
    case 'back':
      goBack()
      break
    case 'retry':
      // 传输层重试：原输入未变，沿用原键
      locked.value = false
      submitKey.value = lastSubmittedKey
      void submit()
      break
    case 'resubmit':
      // 人工重新提交：换新键（`OA-10`「只有传输层重试沿用原键」）
      locked.value = false
      submitKey.value = newIdempotencyKey()
      void submit()
      break
    default:
      break
  }
}

function goBack() {
  void router.push(from.value)
}

/** `OA-03`：成功后「查看详情」按受理结果的货主编号回列表并打开该货主抽屉。 */
function goDetail() {
  void router.push({ path: LIST_PATH, query: { detail: succeededNo.value } })
}

/** 冲突面板里的「刷新查看」与受理条出口同一动作（`UI-X12`）。 */
function refreshLatest() {
  void loadDetail(true)
}

onMounted(() => {
  if (isEdit.value) {
    void loadDetail(false)
  }
})
</script>

<template>
  <!-- UI-P03：页头（标题 + 副标题）→ 单组表单 → 底部固定操作条 -->
  <PageHeader :title="title" :description="subtitle">
    <template #actions>
      <ActionButton variant="secondary" @click="goBack">取消</ActionButton>
    </template>
  </PageHeader>

  <el-card>
    <!-- OA-09：受理条落在页头下方、表单上方，只由受理号驱动 -->
    <AcceptPanel
      :accept-no="acceptNo"
      :poll="pollAcceptance"
      :replayed="replayed"
      :failure-title="isEdit ? '货主档案改档失败' : '货主档案建档失败'"
      :failure-dispatch="describeOwnerAcceptFailure"
      :result-summary="ownerResultSummary"
      @refresh="onRefresh"
      @settled="onSettled"
      @exit="onAcceptExit"
    />

    <!-- OA-03：成功给「查看详情」与「返回列表」两个出口 -->
    <div v-if="succeededNo" class="form-success">
      <span class="form-success__text">货主编号 {{ succeededNo }}</span>
      <ActionButton variant="primary" @click="goDetail">查看详情</ActionButton>
      <ActionButton variant="secondary" @click="goBack">返回列表</ActionButton>
    </div>

    <!-- OA-04：停用态直达时顶部给警告条，提交置灰并给同一原因 -->
    <el-alert
      v-if="isEdit && currentStatus === 'DISABLED'"
      title="该货主已停用，需先启用后再改档"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <!-- OA-10：版本冲突面板就地渲染在表单顶部，给两个出口，不静默合并 -->
    <el-alert
      v-if="conflict"
      title="该货主档案已被他人修改，本次未写入"
      type="warning"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    >
      <template #default>
        <p class="form-note">刷新后按最新数据重新提交；用户已填写的内容不会丢弃。</p>
        <div class="form-note__actions">
          <ActionButton variant="text" @click="refreshLatest">刷新查看</ActionButton>
          <ActionButton variant="text" @click="onAcceptExit('discard')">放弃本次修改</ActionButton>
        </div>
      </template>
    </el-alert>
    <el-alert
      v-else-if="refreshNotice"
      :title="refreshNotice"
      type="info"
      :closable="false"
      show-icon
      style="margin-bottom: 16px"
    />

    <!-- UI-S01：编辑页首次取详情用骨架屏；失败给错误块与重试（UI-S04） -->
    <el-skeleton v-if="detailLoading" :rows="6" animated />
    <template v-else-if="detailError">
      <el-alert :title="detailError" type="error" :closable="false" show-icon>
        <template #default>
          <ActionButton variant="text" @click="loadDetail(false)">重试</ActionButton>
        </template>
      </el-alert>
    </template>

    <el-form v-else label-width="100px" @submit.prevent>
      <FormSection title="货主信息">
        <FormField :label="ownerFieldLabels.ownerName" required :error="errors.ownerName">
          <FormInput
            v-model="form.ownerName"
            data-field="ownerName"
            :disabled="locked"
            :help="helps.ownerName"
            @input="onInput('ownerName')"
            @blur="onBlur('ownerName')"
          />
          <p v-if="changedFields.ownerName" class="form-changed">已变更</p>
        </FormField>
        <FormField :label="ownerFieldLabels.contactName" required :error="errors.contactName">
          <FormInput
            v-model="form.contactName"
            data-field="contactName"
            :disabled="locked"
            @input="onInput('contactName')"
            @blur="onBlur('contactName')"
          />
          <p v-if="changedFields.contactName" class="form-changed">已变更</p>
        </FormField>
        <FormField :label="ownerFieldLabels.contactPhone" required :error="errors.contactPhone">
          <FormInput
            v-model="form.contactPhone"
            data-field="contactPhone"
            :disabled="locked"
            :help="helps.contactPhone"
            @input="onInput('contactPhone')"
            @blur="onBlur('contactPhone')"
          />
          <p v-if="changedFields.contactPhone" class="form-changed">已变更</p>
        </FormField>
        <FormField :label="ownerFieldLabels.creditCode" required :error="errors.creditCode">
          <FormInput
            v-model="form.creditCode"
            data-field="creditCode"
            :disabled="locked"
            :help="helps.creditCode"
            @input="onInput('creditCode')"
            @blur="onBlur('creditCode')"
          />
          <p v-if="changedFields.creditCode" class="form-changed">已变更</p>
        </FormField>
      </FormSection>
    </el-form>

    <!-- 同步与整页级错误的就地错误块（UI-S04） -->
    <el-alert v-if="topError" :title="topError" type="error" :closable="false" show-icon style="margin-bottom: 16px" />

    <!-- UI-C07：底部操作条右对齐，主操作最右；置灰只用于操作前置（UI-I03） -->
    <div class="form-actions">
      <ActionButton variant="secondary" @click="goBack">取消</ActionButton>
      <ActionButton
        variant="primary"
        :loading="submitting"
        :reason="submitReason"
        @click="submit"
      >
        提交
      </ActionButton>
    </div>
  </el-card>
</template>

<style scoped>
/* UI-P03：底部操作条与表单分段之间留 24（UI-T14 的 4 的倍数） */
.form-actions {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-top: 24px;
}
/* OA-03：成功后的两个出口与结果摘要同一行，右对齐 */
.form-success {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  margin-bottom: 16px;
}
.form-success__text {
  margin-right: 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
/* UI-T03：冲突面板里的说明与出口 */
.form-note {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}
.form-note__actions {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
/* UI-T03：被服务端值改过的字段用警告色 + 文字双编码，不靠颜色单独表意 */
.form-changed {
  flex: 0 0 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-color-warning);
}
</style>
