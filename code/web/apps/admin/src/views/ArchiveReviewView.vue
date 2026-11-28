<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessageBox } from 'element-plus'
import {
  AcceptPanel,
  ActionButton,
  ActionDrawer,
  DataTable,
  DegradedBanner,
  DetailDrawer,
  FilterBar,
  FormField,
  FormInput,
  FormSection,
  PageHeader,
  StatusTag,
  formatListTime,
  parseUtcIso,
  toUtcIso,
} from '@wms/design'
import {
  archiveFieldLabels,
  archiveSortOptions,
  archiveStatusLabels,
  archiveStatusOptions,
  archiveStatusTags,
  reviewActionLabels,
  sortDirOptions,
} from '@wms/dict'
import {
  describeApiError,
  newIdempotencyKey,
  type ArchiveItem,
  type ArchiveSortBy,
  type ArchiveStatus,
  type BatchAction,
  type BatchOutcomeItem,
  type ReviewAction,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import { buildArchiveSections, toAcceptancePoll, useHubArchiveApi } from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 运营端商品档案审核（`UI-P01` 列表页 + `UI-C31` 操作抽屉）。
 *
 * 待审核队列没有独立接口，按 API-007 以 `status=PENDING` 过滤读取；
 * 单条审核走 API-008，批量走 API-009，两者都只回受理号与受理时状态快照（D20）；
 * 结果按 API-016 每 3 秒轮询取得（`UI-F02`），批量逐条结果取受理结果的 `items`（`UI-I08`）。
 */
const auth = useAuthStore()
const api = useHubArchiveApi()

const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'ownerId',
  'ownerCode',
  'status',
  'updatedFrom',
  'updatedTo',
  'sortBy',
  'sortDir',
  'filtersCollapsed',
] as const)
/** 进入页面默认只看待审核（`ui.md`「运营端 单条与批量审核」）；用户点查询时再写进地址栏。 */
if (!filter.status) {
  filter.status = 'PENDING'
}

const items = ref<ArchiveItem[]>([])
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
/** 列表查询降级时的数据时间戳：存在即说明本次结果来自副本（契约「降级约定」）。 */
const dataTimestamp = ref<string | null>(null)
/** 受理查询降级时的数据时间戳，由 `AcceptPanel` 的 `degrade` 事件上来（`UI-S05`）。 */
const acceptTimestamp = ref<string | null>(null)
/** 降级提示条只有一个「刷新取实时」出口，受理查询也一并重查（`UI-S07`）。 */
const acceptPanel = ref<{ realtime: () => Promise<void> } | null>(null)
const loading = ref(false)
const error = ref('')
const acceptNo = ref<string | null>(null)

const detailVisible = ref(false)
const detailRow = ref<ArchiveItem | null>(null)

const selected = ref<ArchiveItem[]>([])
const tableRef = ref<{ clearSelection?: () => void } | null>(null)

const submitting = ref(false)
/**
 * 原因抽屉：驳回与批量停用共用同一外壳（`UI-C31`），目标与动作在打开时确定。
 * 两个动作的原因都必填：单条驳回走 API-008 的 `reviewReason`，
 * 批量驳回与批量停用走 API-009 的 `reason`（契约 API-008／API-009「请求」）。
 */
const reasonVisible = ref(false)
const reasonTarget = ref<ArchiveItem | null>(null)
const reasonBatch = ref(false)
const reasonAction = ref<ReviewAction | 'DISABLE'>('REJECT')
const reasonText = ref('')
const reasonError = ref('')

/** 批量逐条结果来自 API-016 的 `result.items`；最近一次批量动作用于失败项单独重试。 */
const batchItems = ref<BatchOutcomeItem[] | null>(null)
const lastBatch = ref<{ action: BatchAction; reason: string } | null>(null)
const batchCounts = computed(() => {
  const list = batchItems.value ?? []
  const succeeded = list.filter((item) => item.success).length
  return { total: list.length, succeeded, failed: list.length - succeeded }
})

const canReview = computed(() => auth.hasPermission(PERMISSIONS.archiveHubReview))
const detailSections = computed(() => (detailRow.value ? buildArchiveSections(detailRow.value) : []))
/** 抽屉标题取字典动作词（`UI-W01`），写着对象或条数，不另造文案。 */
const reasonTitle = computed(() => {
  const action = reviewActionLabels[reasonAction.value] ?? reasonAction.value
  const target = reasonBatch.value ? `已选 ${selected.value.length} 条` : (reasonTarget.value?.name ?? '')
  return `${action} · ${target}`
})
/** 驳回与停用的原因都必填，提示词随动作变（`UI-I03`：置灰并给原因）。 */
const reasonRequiredTip = computed(() => (reasonAction.value === 'DISABLE' ? '停用原因必填' : '驳回原因必填'))
/** 字段标签取字典：驳回用 `reviewReason`，停用用 `disableReason`（`UI-W05` 一个字段一个词）。 */
const reasonFieldLabel = computed(() =>
  reasonAction.value === 'DISABLE' ? archiveFieldLabels.disableReason : archiveFieldLabels.reviewReason,
)
/** UI-S07：同页多个降级数据源只显示一条，提示条取最早的数据时间。 */
const degradeSources = computed(() => [dataTimestamp.value, acceptTimestamp.value])

/** 更新时间范围：控件用界面时间，地址栏与查询串用 UTC 时间戳（契约「时间与金额口径」）。 */
const updatedRange = computed<[Date, Date] | null>({
  get: (): [Date, Date] | null => {
    const from = parseUtcIso(filter.updatedFrom)
    const to = parseUtcIso(filter.updatedTo)
    return from && to ? [from, to] : null
  },
  set: (value: [Date, Date] | null) => {
    filter.updatedFrom = toUtcIso(value?.[0] ?? null)
    filter.updatedTo = toUtcIso(value?.[1] ?? null)
  },
})

/** UI-C02：折叠状态随地址栏保留。 */
const filtersCollapsed = computed(() => filter.filtersCollapsed === '1')
function toggleFilters(collapsed: boolean) {
  filter.filtersCollapsed = collapsed ? '1' : ''
  sync()
}

/**
 * API-016：受理结果查询；批量动作完成时把逐条结果（`result.items`，条目含 `archiveId`、
 * `success`、`status`、`errorCode`、`message`）取回页面按 `UI-I08` 展开。
 * 受理状态、失败错误码、`result` 摘要与降级数据时间一并交给 `AcceptPanel`（`UI-S05`）。
 */
async function pollAcceptance(no: string) {
  const data = await api.getAcceptance(no)
  if (data.acceptStatus === 'SUCCEEDED' && data.result?.items) {
    batchItems.value = data.result.items
  }
  return {
    status: data.acceptStatus,
    errorCode: data.errorCode ?? null,
    result: data.result ?? null,
    dataTimestamp: data.dataTimestamp ?? null,
  }
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.list({
      ownerId: filter.ownerId,
      ownerCode: filter.ownerCode,
      status: filter.status ? (filter.status as ArchiveStatus) : '',
      updatedFrom: filter.updatedFrom,
      updatedTo: filter.updatedTo,
      sortBy: filter.sortBy ? (filter.sortBy as ArchiveSortBy) : '',
      sortDir: filter.sortDir ? (filter.sortDir as SortDir) : '',
      cursor: cursor.value,
      limit: 20,
    })
    items.value = data.items
    hasMore.value = data.hasMore
    nextCursor.value = data.nextCursor
    dataTimestamp.value = data.dataTimestamp ?? null
    syncDetail()
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    loading.value = false
  }
}

function syncDetail() {
  const current = detailRow.value
  if (!current) {
    return
  }
  detailRow.value = items.value.find((item) => item.archiveId === current.archiveId) ?? current
}

function openDetail(row: ArchiveItem) {
  detailRow.value = row
  detailVisible.value = true
}

/** UI-X01：批量选择默认不跨页保留，翻页与查询都清空已选。 */
function clearSelection() {
  selected.value = []
  tableRef.value?.clearSelection?.()
}

function onSelectionChange(rows: ArchiveItem[]) {
  selected.value = rows
}

/** UI-F03：受理结果到达后重取列表（UI-X11：只刷新当前列表）。 */
function onSettled() {
  void load()
}

/** UI-S05：受理查询降级的数据时间由受理条上报，与列表级降级合并成同一条提示。 */
function onAcceptDegrade(timestamp: string | null) {
  acceptTimestamp.value = timestamp
}

/** UI-S05、UI-S07：「刷新取实时」重查全部降级数据源；受理号还在时连受理查询一并重查。 */
function refreshDegraded() {
  if (acceptNo.value && acceptPanel.value) {
    void acceptPanel.value.realtime()
    return
  }
  void load()
}

function onSearch() {
  clearSelection()
  search()
  void load()
}

function onReset() {
  clearSelection()
  reset()
  filter.status = 'PENDING'
  sync()
  void load()
}

function onNext() {
  clearSelection()
  next(nextCursor.value)
  void load()
}

function onPrev() {
  clearSelection()
  prev()
  void load()
}

/** UI-F05／UI-X02：二次确认写清对象与条数，主按钮用动作词。 */
async function confirmThen(message: string, confirmText: string): Promise<boolean> {
  try {
    await ElMessageBox.confirm(message, '请确认', {
      confirmButtonText: confirmText,
      cancelButtonText: '取消',
      type: 'warning',
    })
    return true
  } catch {
    return false
  }
}

/** API-008 单条通过。 */
async function approveOne(row: ArchiveItem) {
  if (submitting.value) {
    return
  }
  const ok = await confirmThen(
    `确认通过档案 ${row.name}（${row.ownerCode}）的审核？通过后档案进入启用，可被单据与库存引用。`,
    reviewActionLabels.APPROVE,
  )
  if (!ok) {
    return
  }
  submitting.value = true
  error.value = ''
  // 单条动作不产生逐条结果，先清掉上一批展示，避免旧结果留在页面上（UI-I08）。
  batchItems.value = null
  try {
    // 契约 API-008 的请求体只有 `action` 与 `reviewReason`，通过时不带原因。
    const ticket = await api.review(row.archiveId, { action: 'APPROVE' }, newIdempotencyKey())
    acceptNo.value = ticket.acceptNo
    detailVisible.value = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** 打开原因抽屉：单条从详情进，批量从批量条进；详情先关，抽屉内不套抽屉（`UI-D09`）。 */
function openReasonDrawer(action: ReviewAction | 'DISABLE', row: ArchiveItem | null) {
  if (row) {
    detailVisible.value = false
  }
  reasonAction.value = action
  reasonBatch.value = row === null
  reasonTarget.value = row
  reasonText.value = ''
  reasonError.value = ''
  reasonVisible.value = true
}

function openRejectOne(row: ArchiveItem) {
  openReasonDrawer('REJECT', row)
}

function openRejectBatch() {
  if (selected.value.length === 0) {
    return
  }
  openReasonDrawer('REJECT', null)
}

/** API-009 批量停用：危险操作，原因必填（契约 API-009「驳回或停用时必填」）。 */
function openDisableBatch() {
  if (selected.value.length === 0) {
    return
  }
  openReasonDrawer('DISABLE', null)
}

/** API-009 批量通过。 */
async function approveBatch() {
  if (submitting.value || selected.value.length === 0) {
    return
  }
  const archiveIds = selected.value.map((row) => row.archiveId)
  const ok = await confirmThen(`对已选 ${archiveIds.length} 条执行通过？`, reviewActionLabels.APPROVE)
  if (!ok) {
    return
  }
  await submitBatch(archiveIds, 'APPROVE', '')
}

/** 批量请求体按契约 API-009 收口为 `archiveIds`、`action`、`reason` 三个字段。 */
async function submitBatch(archiveIds: string[], action: BatchAction, reason: string) {
  submitting.value = true
  error.value = ''
  // 上一批结果先清，逐条结果等受理完成后由 API-016 填回（UI-I08）。
  batchItems.value = null
  try {
    const ticket = await api.batch(
      { archiveIds, action, reason: reason || undefined },
      newIdempotencyKey(),
    )
    acceptNo.value = ticket.acceptNo
    lastBatch.value = { action, reason }
    clearSelection()
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** 驳回与批量停用共用的提交：单条走 API-008 的 `reviewReason`，批量走 API-009 的 `reason`。 */
async function submitReason() {
  const reason = reasonText.value.trim()
  if (!reason) {
    reasonError.value = reasonRequiredTip.value
    return
  }
  if (submitting.value) {
    return
  }
  reasonError.value = ''
  const action = reasonAction.value

  if (reasonBatch.value) {
    const archiveIds = selected.value.map((row) => row.archiveId)
    if (archiveIds.length === 0) {
      return
    }
    const tip =
      action === 'DISABLE'
        ? `对已选 ${archiveIds.length} 条执行停用？停用原因将写入档案留痕，停用后档案不再接新单。`
        : `对已选 ${archiveIds.length} 条执行驳回？驳回原因将写入档案留痕。`
    const ok = await confirmThen(tip, reviewActionLabels[action])
    if (!ok) {
      return
    }
    await submitBatch(archiveIds, action, reason)
    reasonVisible.value = false
    return
  }

  const row = reasonTarget.value
  if (!row || action === 'DISABLE') {
    // 单条停用走货主端的停用动作，运营端只有批量停用入口（契约 API-009）。
    return
  }
  submitting.value = true
  batchItems.value = null
  try {
    const ticket = await api.review(
      row.archiveId,
      { action, reviewReason: reason },
      newIdempotencyKey(),
    )
    acceptNo.value = ticket.acceptNo
    reasonVisible.value = false
  } catch (e) {
    reasonError.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** UI-I08：失败项可单独重试，沿用同一动作与原因。 */
async function retryFailed() {
  const list = batchItems.value
  const last = lastBatch.value
  if (!list || !last) {
    return
  }
  const archiveIds = list.filter((item) => !item.success).map((item) => item.archiveId)
  if (archiveIds.length === 0) {
    return
  }
  await submitBatch(archiveIds, last.action, last.reason)
}

onMounted(load)
</script>

<template>
  <!-- UI-P01：页头给标题，筛选区、表格与分页同容器 -->
  <PageHeader title="档案审核" />
  <el-card>
    <!-- UI-S05、UI-S07：降级数据只出一条提示条，显示最早的数据时间与「刷新取实时」出口 -->
    <DegradedBanner :sources="degradeSources" @refresh="refreshDegraded" />

    <!-- UI-F01~UI-F03：写入只到受理号，结果由 API-016 轮询替换 -->
    <AcceptPanel
      ref="acceptPanel"
      :accept-no="acceptNo"
      :poll="pollAcceptance"
      degrade-notice-in-page
      @refresh="load"
      @settled="onSettled"
      @degrade="onAcceptDegrade"
    />

    <!-- UI-C02：筛选超过 4 项折叠，折叠状态随地址栏保留；UI-X04：排序一并入地址栏 -->
    <FilterBar
      :collapsed="filtersCollapsed"
      @update:collapsed="toggleFilters"
      @search="onSearch"
      @reset="onReset"
    >
      <FormInput v-model="filter.ownerId" placeholder="归属货主" style="width: 180px" />
      <FormInput v-model="filter.ownerCode" placeholder="货主系统编码" style="width: 180px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option
          v-for="status in archiveStatusOptions"
          :key="status"
          :label="archiveStatusLabels[status]"
          :value="status"
        />
      </el-select>
      <el-date-picker
        v-model="updatedRange"
        type="datetimerange"
        start-placeholder="更新起"
        end-placeholder="更新止"
        range-separator="至"
        style="width: 380px"
      />
      <el-select v-model="filter.sortBy" placeholder="排序字段" clearable style="width: 160px">
        <el-option v-for="item in archiveSortOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
      <el-select v-model="filter.sortDir" placeholder="排序方向" clearable style="width: 130px">
        <el-option v-for="item in sortDirOptions" :key="item.value" :label="item.label" :value="item.value" />
      </el-select>
    </FilterBar>

    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom: 16px">
      <template #default>
        <ActionButton variant="text" @click="load">重试</ActionButton>
      </template>
    </el-alert>

    <!-- UI-P01：批量操作条，选中行时出现，已选计数常显；主操作最右（UI-D07） -->
    <div v-if="selected.length > 0 && canReview" class="batch-bar">
      <span class="batch-count">已选 {{ selected.length }} 条</span>
      <ActionButton variant="secondary" :loading="submitting" @click="openRejectBatch">
        {{ reviewActionLabels.REJECT }}
      </ActionButton>
      <!-- API-009 的批量停用动作 DISABLE：危险操作，原因必填 -->
      <ActionButton variant="danger" :loading="submitting" @click="openDisableBatch">
        {{ reviewActionLabels.DISABLE }}
      </ActionButton>
      <ActionButton variant="primary" :loading="submitting" @click="approveBatch">
        {{ reviewActionLabels.APPROVE }}
      </ActionButton>
    </div>

    <DataTable
      v-if="loading || items.length > 0"
      ref="tableRef"
      :data="items"
      :loading="loading"
      table-size="small"
      :has-more="hasMore"
      :has-prev="canPrev"
      @next="onNext"
      @prev="onPrev"
      @selection-change="onSelectionChange"
      @row-click="openDetail"
    >
      <el-table-column type="selection" width="48" />
      <el-table-column prop="ownerCode" :label="archiveFieldLabels.ownerCode" min-width="180" />
      <el-table-column prop="name" :label="archiveFieldLabels.name" min-width="180" />
      <el-table-column prop="ownerId" :label="archiveFieldLabels.ownerId" width="160" />
      <el-table-column :label="archiveFieldLabels.status" width="110">
        <template #default="{ row }">
          <StatusTag :label="archiveStatusLabels[row.status]" :type="archiveStatusTags[row.status]" />
        </template>
      </el-table-column>
      <el-table-column prop="version" :label="archiveFieldLabels.version" width="90" />
      <el-table-column :label="archiveFieldLabels.updatedAt" width="170">
        <template #default="{ row }">{{ formatListTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <ActionButton variant="text" @click.stop="openDetail(row)">详情</ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <el-empty v-else :description="error ? '数据未加载成功' : '暂无待审核档案'">
      <p class="empty-hint">{{ error ? '请按上方提示重试' : '当前筛选条件下没有待审核记录' }}</p>
      <ActionButton variant="secondary" @click="onReset">重置</ActionButton>
    </el-empty>

    <!-- UI-I08：批量结果逐条展示成功与失败原因（结果来自 API-016 的 result.items） -->
    <section v-if="batchItems" class="batch-result">
      <h4>批量结果</h4>
      <p class="batch-summary">
        共 {{ batchCounts.total }} 条，成功 {{ batchCounts.succeeded }} 条，失败 {{ batchCounts.failed }} 条
      </p>
      <el-table :data="batchItems" border size="small">
        <el-table-column prop="archiveId" :label="archiveFieldLabels.archiveId" min-width="180" />
        <el-table-column label="结果" width="100">
          <template #default="{ row }">
            <StatusTag :label="row.success ? '成功' : '失败'" :type="row.success ? 'success' : 'danger'" />
          </template>
        </el-table-column>
        <el-table-column label="当前状态" width="120">
          <template #default="{ row }">
            {{ row.status ? (archiveStatusLabels[row.status] ?? row.status) : '—' }}
          </template>
        </el-table-column>
        <el-table-column label="错误码" min-width="200">
          <template #default="{ row }">{{ row.errorCode ?? '—' }}</template>
        </el-table-column>
        <el-table-column label="说明" min-width="200">
          <template #default="{ row }">{{ row.message ?? '—' }}</template>
        </el-table-column>
      </el-table>
      <div v-if="batchCounts.failed > 0" class="batch-retry">
        <ActionButton variant="secondary" :loading="submitting" @click="retryFailed">重试失败项</ActionButton>
      </div>
    </section>

    <DetailDrawer
      v-model="detailVisible"
      :title="detailRow?.name ?? ''"
      :subtitle="detailRow ? `档案编号 ${detailRow.archiveId}` : undefined"
      :status="detailRow ? archiveStatusLabels[detailRow.status] : undefined"
      :status-type="detailRow ? archiveStatusTags[detailRow.status] : undefined"
      :sections="detailSections"
      :columns="2"
      :width="880"
      :closable="!submitting"
    >
      <template #footer>
        <!-- UI-D07：底部操作最多 4 个，主操作最右；无审核权限不渲染 -->
        <template v-if="canReview && detailRow">
          <ActionButton variant="secondary" :disabled="submitting" @click="openRejectOne(detailRow)">
            {{ reviewActionLabels.REJECT }}
          </ActionButton>
          <ActionButton variant="primary" :loading="submitting" @click="approveOne(detailRow)">
            {{ reviewActionLabels.APPROVE }}
          </ActionButton>
        </template>
      </template>
    </DetailDrawer>

    <ActionDrawer
      v-model="reasonVisible"
      :title="reasonTitle"
      :width="480"
      :closable="!submitting"
    >
      <el-form label-width="100px">
        <FormSection :title="reviewActionLabels[reasonAction]">
          <FormField :label="reasonFieldLabel" required :error="reasonError">
            <el-input
              v-model="reasonText"
              type="textarea"
              :rows="4"
              maxlength="200"
              show-word-limit
              :placeholder="reasonAction === 'DISABLE' ? '写明停用原因' : '写明驳回原因，货主修改后可重提'"
            />
          </FormField>
        </FormSection>
      </el-form>
      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="reasonVisible = false">取消</ActionButton>
        <!-- UI-I03：原因为空时置灰并给原因；UI-I05：提交中禁用 -->
        <ActionButton
          variant="danger"
          :loading="submitting"
          :reason="reasonText.trim() ? '' : reasonRequiredTip"
          @click="submitReason"
        >
          {{ reviewActionLabels[reasonAction] }}
        </ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
/* UI-P01：批量操作条紧贴表格上方，间距取 4 的倍数（UI-T14） */
.batch-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  padding: 0 0 16px;
}
.batch-count {
  margin-right: 12px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
.batch-result {
  margin-top: 24px;
}
.batch-result h4 {
  margin: 0 0 12px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
.batch-summary {
  margin: 0 0 12px;
  font-size: 13px;
  color: var(--el-text-color-regular);
}
.batch-retry {
  margin-top: 16px;
}
.empty-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
