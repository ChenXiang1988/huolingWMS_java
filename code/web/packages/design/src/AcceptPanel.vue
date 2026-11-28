<script setup lang="ts">
import { computed, onUnmounted, ref, watch } from 'vue'
import { acceptanceStatusLabels, acceptanceStatusTags, acceptanceTimeoutLabel } from '@wms/dict'
import { formatListTime } from './format'
import ActionButton from './ActionButton.vue'
import StatusTag from './StatusTag.vue'

/**
 * 受理环展示（12 的 `UI-F01`~`UI-F03`）：
 * 提交后只展示受理号与状态，不出现「提交成功」。
 *
 * 结果来源有两种，由调用方决定：
 * - 传 `poll`：按 `UI-F02` 每 3 秒轮询、最长 60 秒（受理结果查询接口存在时用这条）。
 *   商品档案三端都传：货主端 API-015、运营端 API-016、单仓端 API-017；
 * - 不传 `poll`：本接口契约没有受理结果查询条目，此时只停在「已受理 · 处理中」，
 *   结果随列表刷新到达，并给「刷新」出口（`UI-F01`、`UI-I06`），不发起契约外的请求。
 *
 * 结果摘要按契约 `result` 子字段展示：`result.archiveId`、`result.requestId`，以及
 * `result.items` 的条数与成败（逐条的 `archiveId`、`success`、`status`、`errorCode`、
 * `message` 由承载批量的页面按 `UI-I08` 列表展示，面板不重复一份明细）。
 *
 * 受理查询降级（响应带 `dataTimestamp`）时按 `UI-S05` 显示数据时间并给「刷新取实时」出口，
 * 提示常驻到刷新成功（`UI-S07`），不用空结果或「处理中」掩盖故障。
 * 承载页另有列表级降级提示条时传 `degradeNoticeInPage`：本组件只通过 `degrade` 事件上报数据时间，
 * 由页面按 `UI-S07` 合并成一条提示（取最早的数据时间），同页不出现两条降级提示。
 *
 * 超时按 `UI-F02` 转「稍后查看」，保留受理号并给查询入口，不伪造执行结果；
 * 结果到达按 `UI-F03` 整条替换状态，失败给重提入口并保留原输入（由调用方持有）。
 *
 * 变更 `add-owner-archive`（`ui.md`「OA-09」「OA-10」）在本组件补四处，页面不各写一套：
 * - 首发查询改为受理后 1 秒（覆盖秒级完成），此后每 3 秒，最长 60 秒，超时转「重新查询」；
 * - `replayed` 为真时附「该请求此前已提交，沿用首次受理号」；
 * - 失败按承载页给的分派表呈现三段文案（`UI-W07`）与出口（`OA-10`），错误码照旧展示（`UI-S06`）；
 * - 成功结果摘要的文案由承载页拼（`resultSummary`），本组件只呈现，不猜业务词。
 */

/** 逐条结果条目，字段与契约 `result.items` 一致。 */
export interface AcceptanceItemView {
  archiveId: string
  success: boolean
  status?: string | null
  errorCode?: string | null
  message?: string | null
}

/** 受理结果摘要，字段与契约 `result` 一致，未完成为空。 */
export interface AcceptanceResultView {
  archiveId?: string | null
  requestId?: string | null
  items?: AcceptanceItemView[]
  /** 货主档案受理结果（API-027 的 `result`）：货主编号、业务状态与版本。 */
  ownerId?: string | null
  ownerNo?: string | null
  status?: string | null
  version?: number | null
}

/** 受理失败的一个出口：`code` 由承载页按错误码分派表解释，`label` 是按钮文案。 */
export interface AcceptanceExitView {
  code: string
  label: string
}

/**
 * 受理失败的三段文案与出口（`UI-W07`、`OA-10`）：
 * `message` 是第二段（为什么）、`nextStep` 是第三段（下一步），第一段由承载页的 `failureTitle` 给。
 */
export interface AcceptanceFailureView {
  message: string
  nextStep: string
  exits: AcceptanceExitView[]
}

/** 受理查询响应里本组件要用的部分：受理状态、结果摘要、失败错误码与降级数据时间。 */
export interface AcceptancePollResult {
  status: string
  errorCode?: string | null
  /** 受理失败时服务端给的说明，作为三段文案的兜底来源（不替代分派表）。 */
  errorMessage?: string | null
  result?: AcceptanceResultView | null
  dataTimestamp?: string | null
}

const props = defineProps<{
  acceptNo: string | null
  /**
   * 受理结果查询，返回受理状态、失败错误码与结果摘要；不传表示本端无该接口。
   * 状态取值见契约字段表 `acceptStatus`：`PROCESSING`、`SUCCEEDED`、`FAILED`。
   */
  poll?: (acceptNo: string) => Promise<AcceptancePollResult>
  /**
   * 降级提示由承载页统一展示（`UI-S07`：同页多个降级数据源只显示一条）。
   * 置 true 时本组件不再自绘数据时间与「刷新取实时」，改由 `degrade` 事件把数据时间交给页面；
   * 不传时保持组件自带提示条的行为，供没有页面级提示条的页面使用。
   */
  degradeNoticeInPage?: boolean
  /**
   * 本次受理是否同一幂等键的重复请求（契约 1.6 的 `replayed`）。
   * 为 true 时附一句「该请求此前已提交，沿用首次受理号」，避免被读成重复提交了两次（`OA-09`）。
   */
  replayed?: boolean
  /** 三段文案的第一段（动作 + 对象），如「货主档案建档失败」（`UI-W07`）；不传则不显示三段。 */
  failureTitle?: string
  /**
   * 受理失败的错误码分派（`OA-10`）：由承载页传入页面无关的分派表，组件只负责呈现三段与出口。
   * 不传时退回旧行为：只显示错误码与「重新提交」出口。
   */
  failureDispatch?: (errorCode: string) => AcceptanceFailureView | null
  /**
   * 成功结果摘要的单行文案（货主档案为「货主编号、状态、版本」）。
   * 文案依赖各业务的字典，放在承载页拼，面板只负责呈现（`UI-W11`：说结果不说过程）。
   */
  resultSummary?: (result: AcceptanceResultView) => string
}>()

const emit = defineEmits<{
  (e: 'retry'): void
  (e: 'refresh'): void
  /**
   * 受理终结：`status` 为受理状态，`result` 为结果摘要（未完成为 `null`），
   * `errorCode` 为受理失败时的错误码（成功时为 `null`，供承载页决定冲突面板等分支，`OA-10`）。
   */
  (e: 'settled', status: string, result: AcceptanceResultView | null, errorCode: string | null): void
  /** 受理查询降级时的数据时间，`null` 表示本次查询未降级。 */
  (e: 'degrade', dataTimestamp: string | null): void
  /** 受理失败后点了分派表里的某个出口，`code` 原样回给承载页处理。 */
  (e: 'exit', code: string): void
}>()

/** `UI-F02`：每 3 秒轮询，最长 60 秒；`OA-09` 追加「受理后 1 秒首发」，覆盖秒级完成的情况。 */
const POLL_INTERVAL_MS = 3000
const POLL_FIRST_DELAY_MS = 1000
const POLL_LIMIT_MS = 60_000

/** 受理中：契约的受理状态闭集只有处理中、成功、失败，初值取 `PROCESSING`。 */
const status = ref('PROCESSING')
const errorCode = ref<string | null>(null)
/** 受理失败时服务端给的说明（契约 `errorMessage`），作三段文案第二段的兜底。 */
const errorMessage = ref<string | null>(null)
/** 契约 `result`：受理完成后才有值，未完成为空。 */
const result = ref<AcceptanceResultView | null>(null)
/** 契约 `dataTimestamp`：存在即说明本次受理查询走了降级数据（`UI-S05`）。 */
const dataTimestamp = ref<string | null>(null)
/** UI-F02：超时转「稍后查看」，不再自动轮询，保留受理号查询入口。 */
const timedOut = ref(false)
/** UI-F03：结果到达后给「刷新」出口，让用户看到列表按新结果取到的数据。 */
const settled = ref(false)
/** 首发查询的计时器：`OA-09` 要求受理后 1 秒首发，不立刻打接口。 */
let firstTimer: number | undefined
let timer: number | undefined
let deadline = 0
let generation = 0

const failed = computed(() => status.value === 'FAILED')
/** UI-S05、UI-S07：降级提示常驻到刷新成功，不自动消失。 */
const degraded = computed(() => dataTimestamp.value !== null)
/** 提示条落在本组件内还是承载页：页面级提示条存在时由页面统一出一条（`UI-S07`）。 */
const degradeNotice = computed(() => degraded.value && !props.degradeNoticeInPage)
const label = computed(() => {
  if (timedOut.value) {
    return acceptanceTimeoutLabel
  }
  return acceptanceStatusLabels[status.value] ?? status.value
})
const labelType = computed(() => {
  if (timedOut.value) {
    return 'info' as const
  }
  return acceptanceStatusTags[status.value] ?? 'info'
})
/**
 * `OA-10`：受理失败时的三段文案与出口。
 * 出口与第三段由承载页传入的分派表给出，面板不按页复制一份错误码分支（`UI-W07`、`UI-S06`）。
 */
const failure = computed<AcceptanceFailureView | null>(() => {
  const code = errorCode.value
  if (!failed.value || !code || !props.failureDispatch) {
    return null
  }
  const view = props.failureDispatch(code)
  if (view) {
    return view
  }
  // 分派表未登记的错误码不静默吞掉：给服务端说明作第二段，错误码仍展示（`UI-S06`）。
  return { message: errorMessage.value ?? '', nextStep: '', exits: [] }
})

/** 成功结果的单行摘要：文案由承载页拼，面板只呈现（`UI-W11`）。 */
const summaryText = computed(() => {
  const data = result.value
  if (!data || !props.resultSummary) {
    return ''
  }
  return props.resultSummary(data)
})

/** UI-I08：批量部分成功时先给条数与成败，逐条原因由承载批量的页面列表展开。 */
const itemSummary = computed(() => {
  const list = result.value?.items
  if (!list || list.length === 0) {
    return ''
  }
  const succeeded = list.filter((item) => item.success).length
  return `共 ${list.length} 条，成功 ${succeeded} 条，失败 ${list.length - succeeded} 条`
})

function stop() {
  if (firstTimer) {
    window.clearTimeout(firstTimer)
    firstTimer = undefined
  }
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

/**
 * 把一次受理查询结果写回面板；状态已终结时停轮询并给刷新出口（`UI-F03`）。
 * 返回是否已终结，供轮询据此提前结束本轮（不再进入超时判定）。
 */
function apply(data: AcceptancePollResult, current: number): boolean {
  if (current !== generation) {
    return false
  }
  status.value = data.status
  errorCode.value = data.errorCode ?? null
  errorMessage.value = data.errorMessage ?? null
  result.value = data.result ?? null
  dataTimestamp.value = data.dataTimestamp ?? null
  if (data.status === 'SUCCEEDED' || data.status === 'FAILED') {
    stop()
    timedOut.value = false
    settled.value = true
    emit('settled', data.status, data.result ?? null, data.errorCode ?? null)
    return true
  }
  return false
}

/** 不可重试的拒绝停止轮询；可重试错误继续退避到 60 秒上限（`UI-F02`）。 */
function handleError(error: unknown, current: number): void {
  if (current !== generation) {
    return
  }
  const failure = error as { code?: number | string; retryable?: boolean } | null
  if (failure && !failure.retryable && (typeof failure.code === 'number' || typeof failure.code === 'string')) {
    errorCode.value = String(failure.code)
    stop()
  }
}

async function tick(acceptNo: string, current: number) {
  const poll = props.poll
  if (!poll) {
    return
  }
  try {
    if (apply(await poll(acceptNo), current)) {
      return
    }
  } catch (error) {
    handleError(error, current)
  }
  if (Date.now() >= deadline) {
    stop()
    timedOut.value = true
  }
}

function start(acceptNo: string) {
  stop()
  generation += 1
  const current = generation
  status.value = 'PROCESSING'
  errorCode.value = null
  errorMessage.value = null
  result.value = null
  dataTimestamp.value = null
  timedOut.value = false
  settled.value = false
  // 本端没有受理结果查询接口时不轮询：停在「处理中」，结果由列表刷新到达（UI-F01、UI-I06）。
  if (!props.poll) {
    status.value = 'RUNNING'
    return
  }
  deadline = Date.now() + POLL_LIMIT_MS
  /*
   * `OA-09`：受理后 1 秒首发，覆盖秒级完成的情况；此后每 3 秒一次，最长 60 秒（`UI-F02`）。
   * 首发用 timeout 而不是延时启动 interval，停止条件（结果到达、超时、卸载）只需清两个句柄。
   */
  firstTimer = window.setTimeout(() => {
    firstTimer = undefined
    void tick(acceptNo, current)
    timer = window.setInterval(() => void tick(acceptNo, current), POLL_INTERVAL_MS)
  }, POLL_FIRST_DELAY_MS)
}

/** UI-F02：超时后的「重新查询」入口（`OA-09`）。 */
function resume() {
  if (props.acceptNo) {
    start(props.acceptNo)
  }
}

/**
 * UI-S05 的「刷新取实时」：单次重查受理结果，不重置已到达的状态、不重启轮询节奏；
 * 查询仍失败时保留数据时间（`UI-S07`：常驻到刷新成功），同时刷新承载页面的列表。
 */
async function realtime() {
  const no = props.acceptNo
  const poll = props.poll
  if (!no || !poll) {
    emit('refresh')
    return
  }
  const current = generation
  try {
    apply(await poll(no), current)
  } catch (error) {
    handleError(error, current)
  }
  emit('refresh')
}

/**
 * 承载页的降级提示条只有「刷新取实时」一个出口，受理查询也要一并重查，
 * 否则受理条的数据时间会一直停在旧值、提示条无法常驻到刷新成功（`UI-S07`）。
 */
defineExpose({ realtime })

/** UI-S05、UI-S07：把受理查询的数据时间上报承载页，与列表级降级合并成同一条提示。 */
watch(dataTimestamp, (value) => emit('degrade', value), { immediate: true })

watch(
  () => props.acceptNo,
  (value) => {
    stop()
    generation += 1
    if (!value) {
      status.value = 'PROCESSING'
      errorCode.value = null
      errorMessage.value = null
      result.value = null
      dataTimestamp.value = null
      timedOut.value = false
      settled.value = false
      return
    }
    start(value)
  },
  { immediate: true },
)

onUnmounted(stop)
</script>

<template>
  <!--
    UI-S05、UI-S07：受理查询降级时由这一条受理条就地显示数据时间与「刷新取实时」出口，
    提示常驻到刷新成功；承载页已有自己的降级提示条时（`degradeNoticeInPage`）本组件不再
    自绘这一处，避免同页出现两条降级提示，数据时间改由 `degrade` 事件交给页面合并。
  -->
  <div v-if="acceptNo" class="accept-wrap">
    <div class="accept-panel" :class="{ 'accept-panel--degraded': degradeNotice }">
      <span>受理号 {{ acceptNo }}</span>
      <StatusTag :label="label" :type="labelType" />
      <!-- OA-09：同一幂等键的重复请求只说沿用首次受理号，不被读成重复提交了两次 -->
      <span v-if="replayed" class="accept-hint">该请求此前已提交，沿用首次受理号</span>
      <span v-if="degradeNotice" class="accept-time">数据时间：{{ formatListTime(dataTimestamp) }}</span>
      <!-- UI-F03：成功时整条给出结果摘要（货主档案为货主编号、状态、版本） -->
      <span v-if="result?.archiveId">档案编号 {{ result.archiveId }}</span>
      <span v-if="result?.requestId">申请编号 {{ result.requestId }}</span>
      <span v-if="result?.ownerNo">货主编号 {{ result.ownerNo }}</span>
      <span v-if="summaryText">结果 {{ summaryText }}</span>
      <span v-if="itemSummary">逐条结果 {{ itemSummary }}</span>
      <span v-if="errorCode" class="accept-error">错误码 {{ errorCode }}</span>
      <span v-if="!poll" class="accept-hint">结果随列表刷新到达</span>
      <!--
        UI-F03：结果到达后给刷新出口（UI-X11：只作用于当前列表），不把结果留在提示条里。
        降级时不在此处再出一个刷新出口，避免与降级提示条的「刷新取实时」重复（UI-S05）。
      -->
      <ActionButton v-if="(!poll || settled) && !degraded" variant="text" @click="emit('refresh')">刷新</ActionButton>
      <!-- UI-S05：降级数据的「刷新取实时」出口，重查受理结果并刷新承载页面的列表 -->
      <ActionButton v-if="degradeNotice" variant="text" @click="realtime">刷新取实时</ActionButton>
      <ActionButton v-if="timedOut" variant="text" @click="resume">重新查询</ActionButton>
      <!-- 无分派表时的旧出口；有分派表时改由下方的三段文案与出口承担（OA-10） -->
      <ActionButton v-if="failed && !failureDispatch" variant="text" @click="emit('retry')">重新提交</ActionButton>
    </div>

    <!--
      OA-10、UI-W07：失败给三段文案（做什么失败 / 为什么 / 下一步）与错误码，
      出口按分派表渲染，不出现「操作失败」「系统繁忙」这类空话（UI-S06）。
    -->
    <div v-if="failed && failure" class="accept-failure">
      <p v-if="failureTitle" class="accept-failure__line">{{ failureTitle }}</p>
      <p v-if="failure.message" class="accept-failure__line">{{ failure.message }}</p>
      <p v-if="failure.nextStep" class="accept-failure__line">
        {{ failure.nextStep }}<template v-if="errorCode">（{{ errorCode }}）</template>
      </p>
      <div v-if="failure.exits.length" class="accept-failure__exits">
        <ActionButton
          v-for="exit in failure.exits"
          :key="exit.code"
          variant="text"
          @click="emit('exit', exit.code)"
        >
          {{ exit.label }}
        </ActionButton>
      </div>
    </div>
  </div>
</template>

<style scoped>
/*
 * UI-T14：间距单一来源、不叠加。按钮之间的 12px 由 ActionButton 的相邻兄弟规则
 * （`.action-button + .action-button`）承担，容器不再设 gap，避免叠加成 24px。
 */
.accept-panel {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  min-height: 32px;
  padding: 8px 12px;
  margin-bottom: 16px;
  background: var(--el-fill-color-light);
  border-radius: 4px;
  box-sizing: border-box;
  font-size: 13px;
}
/* UI-S05：降级数据用警告色浅底，与正常受理条区分；不用颜色单独表意（文字已写明数据时间） */
.accept-panel--degraded {
  background: var(--el-color-warning-light-9);
}
/* UI-S05：数据时间用警告色文字，与状态标签一起构成「颜色 + 文字」双编码 */
.accept-time {
  color: var(--el-color-warning-dark-2);
}
/*
 * 本容器混排「受理号文本」「状态标签」「错误码文本」与按钮，
 * 上面删掉 gap 后非按钮元素之间会失去间距，由容器自己补回 12px；
 * 末位元素不留尾距，避免右侧内边距被撑成 24px。
 */
.accept-panel > :not(.action-button):not(:last-child) {
  margin-right: 12px;
}
.accept-error {
  color: var(--el-color-danger);
}
/* 无受理结果查询接口时说明结果来源，辅助色，不与错误码抢视觉（UI-F01） */
.accept-hint {
  color: var(--el-text-color-secondary);
}
/*
 * OA-10、UI-W07：失败三段就地显示在受理条下方（UI-S04 区域内错误块、UI-C23 不用通知承载错误）。
 * 背景取危险色最浅阶，三段各占一行、段内不换行缩进，错误码随第三段呈现（UI-S06）。
 */
.accept-failure {
  margin: -8px 0 16px;
  padding: 12px;
  border-radius: 4px;
  background: var(--el-color-danger-light-9);
}
/* UI-T12：错误文案取辅助信息档，行高按 UI-T13 */
.accept-failure__line {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-color-danger);
}
.accept-failure__exits {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
</style>
