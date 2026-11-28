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
  formatDetailTime,
  formatListTime,
} from '@wms/design'
import {
  archiveChangeFieldLabels,
  archiveSortOptions,
  changeRequestFieldLabels,
  changeRequestStatusLabels,
  changeRequestStatusTags,
  reviewActionLabels,
  sortDirOptions,
} from '@wms/dict'
import {
  describeApiError,
  newIdempotencyKey,
  type ArchiveSortBy,
  type ChangeRequestItem,
  type ChangeRequestStatus,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import { fieldText, toAcceptancePoll, useHubArchiveApi } from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 运营端单仓变更申请审核（`UI-P01` 列表页 + `UI-D01` 详情抽屉 + `UI-C31` 操作抽屉）。
 * 查询走 API-012，审核走 API-013；审核只回受理号与受理时状态快照（D20），
 * 结果由 API-016 每 3 秒轮询取得（`UI-F02`）；写回权威档案与递增版本由服务端完成，前端不判定（`UI-I11`）。
 */
const auth = useAuthStore()
const api = useHubArchiveApi()

/** 查询条件与排序按 `UI-X04` 写进地址栏；筛选超过 4 项时折叠状态一并保留（`UI-C02`）。 */
const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'ownerId',
  'siteId',
  'status',
  'sortBy',
  'sortDir',
  'filtersCollapsed',
] as const)

const items = ref<ChangeRequestItem[]>([])
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
const detailRow = ref<ChangeRequestItem | null>(null)

const submitting = ref(false)
const rejectVisible = ref(false)
/** 驳回原因输入值（提交时按契约字段名 `reviewReason` 上行，本地变量不另起接口字段名）。 */
const rejectText = ref('')
const rejectError = ref('')

const canReview = computed(() => auth.hasPermission(PERMISSIONS.archiveHubReview))
/** API-016：受理结果查询（`AcceptPanel` 按 `UI-F02` 轮询，超时保留受理号）。 */
const pollAcceptance = toAcceptancePoll(api)
/** UI-S07：同页多个降级数据源只显示一条，提示条取最早的数据时间。 */
const degradeSources = computed(() => [dataTimestamp.value, acceptTimestamp.value])

/** UI-C02：折叠状态随地址栏保留。 */
const filtersCollapsed = computed(() => filter.filtersCollapsed === '1')
function toggleFilters(collapsed: boolean) {
  filter.filtersCollapsed = collapsed ? '1' : ''
  sync()
}

/**
 * 详情分区顺序按 `UI-D05`：基础信息 → 明细 → 操作记录，没有的分区跳过。
 * 字段名逐字取契约响应：申请编号 `requestNo`、本仓 `siteId`、驳回原因 `reviewReason`；
 * 契约没有档案名称、审核人与生效时间，故不展示这些字段，免成空值。
 */
const detailSections = computed(() => {
  const row = detailRow.value
  if (!row) {
    return []
  }
  return [
    {
      title: '申请信息',
      fields: [
        { label: changeRequestFieldLabels.requestNo, value: fieldText(row.requestNo) },
        { label: changeRequestFieldLabels.archiveId, value: fieldText(row.archiveId) },
        { label: changeRequestFieldLabels.ownerId, value: fieldText(row.ownerId) },
        { label: changeRequestFieldLabels.siteId, value: fieldText(row.siteId) },
        { label: changeRequestFieldLabels.createdAt, value: formatDetailTime(row.createdAt) },
      ],
    },
    {
      title: '变更内容',
      fields: [
        {
          label: changeRequestFieldLabels.changeField,
          value: archiveChangeFieldLabels[row.changeField] ?? row.changeField,
        },
        { label: changeRequestFieldLabels.targetValue, value: fieldText(row.targetValue) },
        { label: changeRequestFieldLabels.reason, value: fieldText(row.reason) },
      ],
    },
    {
      title: '审核记录',
      fields: [
        { label: changeRequestFieldLabels.status, value: changeRequestStatusLabels[row.status] ?? row.status },
        { label: changeRequestFieldLabels.reviewReason, value: fieldText(row.reviewReason) },
        { label: changeRequestFieldLabels.updatedAt, value: formatDetailTime(row.updatedAt) },
      ],
    },
  ]
})

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.listChangeRequests({
      ownerId: filter.ownerId,
      siteId: filter.siteId,
      status: filter.status ? (filter.status as ChangeRequestStatus) : '',
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
  detailRow.value = items.value.find((item) => item.requestNo === current.requestNo) ?? current
}

function openDetail(row: ChangeRequestItem) {
  detailRow.value = row
  detailVisible.value = true
}

/** UI-F03：受理结果到达后重取列表。 */
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
  search()
  void load()
}

function onReset() {
  reset()
  void load()
}

function onNext() {
  next(nextCursor.value)
  void load()
}

function onPrev() {
  prev()
  void load()
}

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

/** API-013 通过：写回权威档案并递增版本由服务端完成；路径参数取契约字段 `requestNo`。 */
async function approve(row: ChangeRequestItem) {
  if (submitting.value) {
    return
  }
  const ok = await confirmThen(
    `确认通过申请 ${row.requestNo} 的字段变更？通过后写回档案并递增版本。`,
    reviewActionLabels.APPROVE,
  )
  if (!ok) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    // 契约 API-013 的请求体只有 `action` 与 `reviewReason`，通过时不带原因。
    const ticket = await api.reviewChangeRequest(row.requestNo, { action: 'APPROVE' }, newIdempotencyKey())
    acceptNo.value = ticket.acceptNo
    detailVisible.value = false
  } catch (e) {
    error.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** 驳回：先关详情再开操作抽屉（`UI-D09`），原因必填。 */
function openReject(row: ChangeRequestItem) {
  detailVisible.value = false
  detailRow.value = row
  rejectText.value = ''
  rejectError.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  const row = detailRow.value
  if (!row) {
    return
  }
  const reason = rejectText.value.trim()
  if (!reason) {
    rejectError.value = '驳回原因必填'
    return
  }
  if (submitting.value) {
    return
  }
  submitting.value = true
  rejectError.value = ''
  try {
    // 驳回原因取契约字段名 `reviewReason`，契约未在请求体里定义 `version`，故不带。
    const ticket = await api.reviewChangeRequest(
      row.requestNo,
      { action: 'REJECT', reviewReason: reason },
      newIdempotencyKey(),
    )
    acceptNo.value = ticket.acceptNo
    rejectVisible.value = false
  } catch (e) {
    rejectError.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <!-- UI-P01：页头给标题，筛选区、表格与分页同容器 -->
  <PageHeader title="变更申请审核" />
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
      <FormInput v-model="filter.siteId" placeholder="本仓" style="width: 160px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option
          v-for="(label, code) in changeRequestStatusLabels"
          :key="code"
          :label="label"
          :value="code"
        />
      </el-select>
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

    <DataTable
      v-if="loading || items.length > 0"
      :data="items"
      :loading="loading"
      table-size="small"
      :has-more="hasMore"
      :has-prev="canPrev"
      @next="onNext"
      @prev="onPrev"
      @row-click="openDetail"
    >
      <el-table-column prop="requestNo" :label="changeRequestFieldLabels.requestNo" min-width="180" />
      <el-table-column prop="archiveId" :label="changeRequestFieldLabels.archiveId" min-width="180" />
      <el-table-column :label="changeRequestFieldLabels.changeField" width="130">
        <template #default="{ row }">{{ archiveChangeFieldLabels[row.changeField] ?? row.changeField }}</template>
      </el-table-column>
      <el-table-column prop="targetValue" :label="changeRequestFieldLabels.targetValue" min-width="160" />
      <el-table-column prop="siteId" :label="changeRequestFieldLabels.siteId" width="150" />
      <el-table-column :label="changeRequestFieldLabels.status" width="110">
        <template #default="{ row }">
          <StatusTag
            :label="changeRequestStatusLabels[row.status] ?? row.status"
            :type="changeRequestStatusTags[row.status] ?? 'info'"
          />
        </template>
      </el-table-column>
      <el-table-column :label="changeRequestFieldLabels.createdAt" width="170">
        <template #default="{ row }">{{ formatListTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="90" fixed="right">
        <template #default="{ row }">
          <ActionButton variant="text" @click.stop="openDetail(row)">详情</ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <el-empty v-else :description="error ? '数据未加载成功' : '暂无变更申请'">
      <p class="empty-hint">{{ error ? '请按上方提示重试' : '当前筛选条件下没有记录' }}</p>
      <ActionButton variant="secondary" @click="onReset">重置</ActionButton>
    </el-empty>

    <DetailDrawer
      v-model="detailVisible"
      :title="detailRow ? `变更申请 ${detailRow.requestNo}` : ''"
      :subtitle="detailRow ? `档案编号 ${detailRow.archiveId}` : undefined"
      :status="detailRow ? (changeRequestStatusLabels[detailRow.status] ?? detailRow.status) : undefined"
      :status-type="detailRow ? (changeRequestStatusTags[detailRow.status] ?? 'info') : undefined"
      :sections="detailSections"
      :columns="2"
      :width="880"
      :closable="!submitting"
    >
      <template #footer>
        <template v-if="canReview && detailRow">
          <ActionButton variant="secondary" :disabled="submitting" @click="openReject(detailRow)">
            {{ reviewActionLabels.REJECT }}
          </ActionButton>
          <ActionButton variant="primary" :loading="submitting" @click="approve(detailRow)">
            {{ reviewActionLabels.APPROVE }}
          </ActionButton>
        </template>
      </template>
    </DetailDrawer>

    <ActionDrawer
      v-model="rejectVisible"
      :title="`驳回 · 申请 ${detailRow?.requestNo ?? ''}`"
      :width="480"
      :closable="!submitting"
    >
      <el-form label-width="100px">
        <FormSection title="驳回">
          <FormField :label="changeRequestFieldLabels.reviewReason" required :error="rejectError">
            <el-input v-model="rejectText" type="textarea" :rows="4" maxlength="200" show-word-limit placeholder="写明驳回原因，单仓修改后可重新发起" />
          </FormField>
        </FormSection>
      </el-form>
      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="rejectVisible = false">取消</ActionButton>
        <ActionButton
          variant="danger"
          :loading="submitting"
          :reason="rejectText.trim() ? '' : '驳回原因必填'"
          @click="submitReject"
        >
          {{ reviewActionLabels.REJECT }}
        </ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
.empty-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
