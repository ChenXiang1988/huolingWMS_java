<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  AcceptPanel,
  ActionButton,
  DataTable,
  DetailDrawer,
  FilterBar,
  FormInput,
  ImpactDialog,
  PageHeader,
  StatusTag,
  formatListTime,
  parseUtcIso,
  toUtcIso,
} from '@wms/design'
import {
  ownerFieldLabels,
  ownerStatusLabels,
  ownerStatusOptions,
  ownerStatusTags,
} from '@wms/dict'
import {
  ApiError,
  describeApiError,
  newIdempotencyKey,
  type OwnerArchiveDetail,
  type OwnerArchiveItem,
  type OwnerSortBy,
  type OwnerStatus,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import { useListQuery } from '../composables/useListQuery'
import {
  buildOwnerSections,
  describeOwnerAcceptFailure,
  ownerDisableImpacts,
  ownerEnableImpacts,
  ownerResultSummary,
  toOwnerAcceptancePoll,
  useHubOwnerArchiveApi,
} from '../api/owner-archive'

/**
 * 运营端货主档案列表（`UI-P01` 骨架、`ui.md`「OA-01」「OA-02」「OA-05」「OA-06」）。
 *
 * 查询走 API-021（四项常显筛选 + 信用代码折叠、三列排序、键集分页），详情按 API-022 单独取
 * （列表缺 `lastStatusChange` 与创建/修改人，不能只用行数据），停用与启用走 API-025／API-026。
 * 写入只拿 202 受理票据（D20），结果由 API-027 轮询取得，页面不做业务判定（`UI-I11`）：
 * 能否停用、能否改档、信用代码是否重复一律以服务端返回为准。
 */
const auth = useAuthStore()
const api = useHubOwnerArchiveApi()
const route = useRoute()
const router = useRouter()
/** API-027：受理结果查询（首发 1 秒、此后 3 秒、最长 60 秒由 `AcceptPanel` 承担）。 */
const pollAcceptance = toOwnerAcceptancePoll(api)

const LIST_PATH = '/hub/owner-archives'

/**
 * 查询条件、排序、每页条数与游标进地址栏（`UI-X04`）；`detail` 只用于定位抽屉，
 * 不参与列表查询——它不是筛选条件，进 keys 只为跟着地址栏一起保留与清除（`design.md`「状态与数据归属」）。
 */
const { filter, cursor, limit, canPrev, search, reset, next, prev, setLimit, sync } = useListQuery([
  'ownerNo',
  'ownerName',
  'status',
  'creditCode',
  'updatedFrom',
  'updatedTo',
  'sortBy',
  'sortDir',
  'filtersCollapsed',
  'detail',
] as const)

const items = ref<OwnerArchiveItem[]>([])
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
const loading = ref(false)
const error = ref('')
/** 无权限（`FORBIDDEN_OWNER`／`AUTHZ_2001`）按 `UI-S03` 整页处理，不显示成空列表。 */
const forbidden = ref(false)

/** 同一页面只保留最近一次受理条（`OA-09`）：`acceptNo` 单值即代表「最近一条」。 */
const acceptNo = ref<string | null>(null)
const replayed = ref(false)
/** 三段文案的第一段与停用/启用出口的动作词随最近一次写操作而定。 */
const failureTitle = ref('')
/** 最近一次写操作，供失败出口的「重试」「重新提交」「修改输入」复用（`OA-10`）。 */
const lastOp = ref<StatusOp | null>(null)
/** 最近一次实际下发的幂等键，供「重试」沿用原键（`OA-10`：只有传输层重试沿用原键）。 */
let lastSubmittedKey = ''

const canWrite = computed(() => auth.hasPermission(PERMISSIONS.ownerHubWrite))
/** 建档要求数据域为「全部」（契约 API-023）；此处只决定入口显隐与一句说明，不构成准入。 */
const allOwners = computed(() => auth.profile?.dataScope.allOwners ?? false)
const pageHint = computed(() =>
  canWrite.value && !allOwners.value ? '当前账号的数据域不是「全部」货主，不能建档' : '',
)

/** 详情抽屉：`detail` 参数即抽屉的定位来源，关闭时从地址栏去掉（`UI-D08` 列表上下文不丢）。 */
const detail = ref<OwnerArchiveDetail | null>(null)
const detailLoading = ref(false)
const detailError = ref('')
const detailVisible = computed({
  get: () => !!filter.detail,
  set: (visible: boolean) => {
    if (!visible) {
      filter.detail = ''
      sync()
    }
  },
})

const detailSections = computed(() => (detail.value ? buildOwnerSections(detail.value) : []))
const detailStatusLabel = computed(() =>
  detail.value ? (ownerStatusLabels[detail.value.status] ?? detail.value.status) : undefined,
)
const detailStatusType = computed(() =>
  detail.value ? ownerStatusTags[detail.value.status] : undefined,
)

/** 停用与启用共用同一个弹窗（`UI-C05`），只换动作词、影响清单与接口。 */
type StatusAction = 'disable' | 'enable'
interface StatusOp {
  action: StatusAction
  ownerNo: string
  ownerName: string
  creditCode: string
  version: number
  reason: string
}
const statusDialog = reactive({
  visible: false,
  action: 'disable' as StatusAction,
  submitting: false,
})
const dialogRef = ref<{ setReason: (value: string) => void } | null>(null)
/** 弹窗关闭后同步错误就地显示在列表页顶部（受理类失败改由受理条承担，`OA-05`）。 */
const syncError = ref('')

/** 更新时间范围：控件用界面时间，地址栏与查询串用 UTC（契约「时间与金额口径」）。 */
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

/** `UI-C12`：更新时间范围的三个快捷项。 */
const dateShortcuts = [
  { text: '今天', value: () => rangeOf(0) },
  { text: '近 7 天', value: () => rangeOf(6) },
  { text: '近 30 天', value: () => rangeOf(29) },
]

function rangeOf(daysAgo: number): [Date, Date] {
  const end = new Date()
  const start = new Date()
  start.setDate(start.getDate() - daysAgo)
  start.setHours(0, 0, 0, 0)
  return [start, end]
}

/** `UI-C02`：折叠状态随地址栏保留。 */
const filtersCollapsed = computed(() => filter.filtersCollapsed === '1')
function toggleFilters(collapsed: boolean) {
  filter.filtersCollapsed = collapsed ? '1' : ''
  sync()
}

/** 是否有任何筛选条件：空态据此分「无档案」与「筛选过窄」两套文案（`OA-01`）。 */
const filtered = computed(() =>
  [
    filter.ownerNo,
    filter.ownerName,
    filter.status,
    filter.creditCode,
    filter.updatedFrom,
    filter.updatedTo,
  ].some((value) => value !== ''),
)

/** 错误码判定只在这里做一次：数据域与权限类错误整页按 `UI-S03` 处理，其余给区域内错误块。 */
function isForbidden(e: unknown): boolean {
  return e instanceof ApiError && (e.code === 'FORBIDDEN_OWNER' || e.code === 2001)
}

async function load() {
  loading.value = true
  error.value = ''
  forbidden.value = false
  try {
    const data = await api.list({
      ownerNo: filter.ownerNo,
      ownerName: filter.ownerName,
      status: filter.status ? (filter.status as OwnerStatus) : '',
      creditCode: filter.creditCode,
      updatedFrom: filter.updatedFrom,
      updatedTo: filter.updatedTo,
      sortBy: filter.sortBy ? (filter.sortBy as OwnerSortBy) : '',
      sortDir: filter.sortDir ? (filter.sortDir as SortDir) : '',
      cursor: cursor.value,
      limit: limit.value,
    })
    items.value = data.items
    hasMore.value = data.hasMore
    nextCursor.value = data.nextCursor
  } catch (e) {
    forbidden.value = isForbidden(e)
    error.value = describeApiError(e)
  } finally {
    loading.value = false
  }
}

/**
 * 取详情：列表行数据不够渲染三段（缺 `lastStatusChange` 与创建/修改人），必须按 API-022 单取。
 * 抽屉内 `NOT_FOUND_OWNER` 按不存在处理，给错误块与「关闭」出口（`OA-02`）。
 */
async function loadDetail(ownerNo: string) {
  detailLoading.value = true
  detailError.value = ''
  try {
    detail.value = await api.detail(ownerNo)
  } catch (e) {
    detail.value = null
    detailError.value = describeApiError(e)
  } finally {
    detailLoading.value = false
  }
}

/** 行点击、货主编号点击、行内「详情」三者行为一致（`OA-02`）。 */
function openDetail(ownerNo: string) {
  filter.detail = ownerNo
  sync()
  syncError.value = ''
  void loadDetail(ownerNo)
}

/** `OA-02`：行点击打开详情抽屉，与货主编号点击、行内「详情」同一入口。 */
function onRowClick(row: OwnerArchiveItem) {
  openDetail(row.ownerNo)
}

/** 抽屉内「关闭」或确认弹窗前的主动关闭：去掉 `detail` 参数，列表筛选与游标原样保留。 */
function closeDetail() {
  filter.detail = ''
  sync()
}

function onSearch() {
  closeDetail()
  search()
  void load()
}

function onReset() {
  closeDetail()
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

/** 改每页条数：游标回起点并清空上一页栈（2.11、`UI-I09`），不显示总数。 */
function onLimitChange(value: number) {
  setLimit(value)
  void load()
}

/** 排序只认契约白名单的三列（`ownerNo`／`ownerName`／`updateTime`），白名单外的列不挂排序图标。 */
function onSortChange(payload: { prop: string; order: 'ascending' | 'descending' | null }) {
  if (!payload.order) {
    filter.sortBy = ''
    filter.sortDir = ''
  } else {
    filter.sortBy = payload.prop
    filter.sortDir = payload.order === 'ascending' ? 'asc' : 'desc'
  }
  closeDetail()
  search()
  void load()
}

function goCreate() {
  void router.push({ name: 'hub-owner-archive-create', query: { from: route.fullPath } })
}

/** `OA-04`：详情抽屉的「编辑」→ 关抽屉 → 打开表单页（`UI-D09` 抽屉内不套抽屉）。 */
function goEdit() {
  const current = detail.value
  if (!current) {
    return
  }
  const from = router.resolve({ path: LIST_PATH, query: route.query }).fullPath
  closeDetail()
  void router.push({
    name: 'hub-owner-archive-edit',
    params: { ownerNo: current.ownerNo },
    query: { from },
  })
}

/**
 * `OA-05`／`OA-06`：抽屉底部点动作（第一次确认意图）→ 关抽屉 → 弹窗填原因（第二次确认）。
 * 目标对象与进入时的 `version` 一并记下：版本号随详情取到的最新值走，停用与启用必带（契约 API-025／026）。
 */
function openStatusDialog(action: StatusAction) {
  const current = detail.value
  if (!current) {
    return
  }
  lastOp.value = {
    action,
    ownerNo: current.ownerNo,
    ownerName: current.ownerName,
    creditCode: current.creditCode,
    version: current.version,
    reason: '',
  }
  statusDialog.action = action
  statusDialog.visible = true
  syncError.value = ''
  closeDetail()
  // 原因在失败重提时保留（`UI-F03`），打开新动作时先清空
  dialogRef.value?.setReason('')
}

const dialogTitle = computed(() =>
  statusDialog.action === 'disable' ? '停用 · 货主档案' : '启用 · 货主档案',
)
const dialogSubject = computed(() => {
  const op = lastOp.value
  return op ? `${op.ownerName}（${op.ownerNo}）` : ''
})
const dialogImpacts = computed(() =>
  statusDialog.action === 'disable' ? ownerDisableImpacts : ownerEnableImpacts,
)
const dialogConfirmText = computed(() => (statusDialog.action === 'disable' ? '停用' : '启用'))

/**
 * 发起停用或启用：只拿 202 受理票据（`UI-F01`），随后由受理条轮询 API-027（`OA-09`）。
 * 人工重提换新键、传输层重试沿用原键的口径由 `OA-10` 的幂等键列决定。
 */
async function submitStatus(op: StatusOp, idempotencyKey: string) {
  lastOp.value = op
  // 记下本次下发的键：受理失败后的「重试」沿用原键、「重新提交」换新键（`OA-10` 表末列）
  lastSubmittedKey = idempotencyKey
  failureTitle.value = op.action === 'disable' ? '货主档案停用失败' : '货主档案启用失败'
  syncError.value = ''
  try {
    const body = { reason: op.reason, version: op.version }
    const ticket =
      op.action === 'disable'
        ? await api.disable(op.ownerNo, body, idempotencyKey)
        : await api.enable(op.ownerNo, body, idempotencyKey)
    acceptNo.value = ticket.acceptNo
    replayed.value = ticket.replayed === true
    statusDialog.visible = false
  } catch (e) {
    // 同步错误没有受理号：关闭弹窗后在列表页就地给三段文案与重试出口（`OA-05`）
    statusDialog.visible = false
    syncError.value = describeApiError(e)
    if (isForbidden(e)) {
      forbidden.value = true
    }
  }
}

async function onConfirmStatus(reason: string) {
  const op = lastOp.value
  if (!op || statusDialog.submitting) {
    return
  }
  const next: StatusOp = { ...op, reason }
  statusDialog.submitting = true
  try {
    await submitStatus(next, newIdempotencyKey())
  } finally {
    statusDialog.submitting = false
  }
}

/** 受理终结：成功按 `UI-F04` 给 2 秒轻提示，并按 `UI-X11` 只重取数据、不清筛选与游标。 */
function onSettled(status: string, result: { ownerNo?: string | null } | null) {
  if (status !== 'SUCCEEDED') {
    return
  }
  const ownerNo = result?.ownerNo ?? lastOp.value?.ownerNo ?? ''
  const action = lastOp.value?.action
  const message =
    action === 'disable'
      ? `货主档案已停用，货主编号 ${ownerNo}`
      : `货主档案已启用，货主编号 ${ownerNo}`
  ElMessage({ message, type: 'success', duration: 2000 })
  void load()
  if (filter.detail) {
    void loadDetail(filter.detail)
  }
}

/**
 * 受理失败的出口分派（`OA-10`）：出口编码由单处分派表给，页面只做动作。
 * `retry` 用原键（传输层重试）、`resubmit` 换新键（人工重新提交），不以页面各自判断键的处置。
 */
function onAcceptExit(code: string) {
  const op = lastOp.value
  switch (code) {
    case 'refresh':
      void load()
      if (filter.detail) {
        void loadDetail(filter.detail)
      }
      break
    case 'back':
    case 'discard':
      closeDetail()
      break
    case 'editInput':
      if (op) {
        statusDialog.action = op.action
        statusDialog.visible = true
        dialogRef.value?.setReason(op.reason)
      }
      break
    case 'filterByCreditCode':
      if (op?.creditCode) {
        filter.creditCode = op.creditCode
        filter.detail = ''
        search()
        void load()
      }
      break
    case 'retry':
      // 传输层重试：沿用原键（`OA-10` 表末列标 `keep` 的几种失败）
      if (op) {
        void submitStatus(op, lastSubmittedKey || newIdempotencyKey())
      }
      break
    case 'resubmit':
      // 人工重新提交：换新键，避免命中同键异体（`OA-10`「只有传输层重试沿用原键」）
      if (op) {
        void submitStatus(op, newIdempotencyKey())
      }
      break
    default:
      break
  }
}

/** 详情失败的重试：仍以地址栏里的 `detail` 为准，不另存对象。 */
function retryDetail() {
  if (filter.detail) {
    void loadDetail(filter.detail)
  }
}

onMounted(() => {
  void load()
  // 刷新后从地址栏还原抽屉（`OA-01` 的恢复路径）；`detail` 不是筛选条件，不参与列表查询
  if (filter.detail) {
    void loadDetail(filter.detail)
  }
})
</script>

<template>
  <!-- UI-P01：页头给标题与主操作，筛选区、表格与分页同容器 -->
  <PageHeader title="货主档案" :description="pageHint">
    <template #actions>
      <!-- UI-X06：无功能权限或数据域非全域时不渲染入口，不置灰（OA-03） -->
      <ActionButton v-if="canWrite && allOwners" variant="primary" @click="goCreate">新建</ActionButton>
    </template>
  </PageHeader>

  <el-card>
    <!-- UI-S03：数据域为空或功能权限不足时整页说明，不显示成空列表 -->
    <el-result v-if="forbidden" icon="warning" title="无权访问">
      <template #sub-title>
        <p class="owner-block__line">当前账号没有该数据域的货主档案，或缺少所需权限点。</p>
        <p class="owner-block__line">如需开通，请联系权限管理员分配对应角色。</p>
      </template>
      <template #extra>
        <ActionButton variant="secondary" @click="router.back()">返回上一页</ActionButton>
      </template>
    </el-result>

    <template v-else>
      <!-- UI-F01~UI-F03、OA-09：写入只到受理号，结果由 API-027 轮询替换 -->
      <AcceptPanel
        :accept-no="acceptNo"
        :poll="pollAcceptance"
        :replayed="replayed"
        :failure-title="failureTitle"
        :failure-dispatch="describeOwnerAcceptFailure"
        :result-summary="ownerResultSummary"
        @refresh="load"
        @settled="onSettled"
        @exit="onAcceptExit"
      />

      <!-- UI-S04：同步错误就地给三段文案与重试出口，保留筛选与游标 -->
      <el-alert v-if="syncError" :title="syncError" type="error" :closable="false" show-icon style="margin-bottom: 16px">
        <template #default>
          <ActionButton variant="text" @click="onAcceptExit('retry')">重试</ActionButton>
        </template>
      </el-alert>

      <!-- UI-C02：常显 4 项 + 信用代码折叠；折叠状态随地址栏保留 -->
      <FilterBar
        :collapsed="filtersCollapsed"
        @update:collapsed="toggleFilters"
        @search="onSearch"
        @reset="onReset"
      >
        <FormInput v-model="filter.ownerNo" placeholder="货主编号" style="width: 160px" />
        <FormInput v-model="filter.ownerName" placeholder="货主名称" style="width: 200px" />
        <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
          <el-option
            v-for="status in ownerStatusOptions"
            :key="status"
            :label="ownerStatusLabels[status]"
            :value="status"
          />
        </el-select>
        <el-date-picker
          v-model="updatedRange"
          type="datetimerange"
          start-placeholder="更新起"
          end-placeholder="更新止"
          range-separator="至"
          :shortcuts="dateShortcuts"
          style="width: 360px"
        />
        <FormInput v-model="filter.creditCode" placeholder="信用代码" style="width: 200px" />
      </FilterBar>

      <!-- UI-S04：列表失败保留筛选、排序与游标，给重试出口 -->
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
        :limit="limit"
        @next="onNext"
        @prev="onPrev"
        @update:limit="onLimitChange"
        @sort-change="onSortChange"
        @row-click="onRowClick"
      >
        <!-- 货主编号：可点进详情，超宽省略并悬浮全号（UI-B08、OA-02） -->
        <el-table-column
          prop="ownerNo"
          :label="ownerFieldLabels.ownerNo"
          width="160"
          sortable="custom"
        >
          <template #default="{ row }">
            <!-- 完整不截断、超宽省略并悬浮全号（UI-B08）；点击与行点击行为一致（OA-02） -->
            <span class="owner-no" :title="row.ownerNo" @click.stop="openDetail(row.ownerNo)">
              {{ row.ownerNo }}
            </span>
          </template>
        </el-table-column>
        <el-table-column
          prop="ownerName"
          :label="ownerFieldLabels.ownerName"
          min-width="200"
          sortable="custom"
        />
        <el-table-column prop="contactName" :label="ownerFieldLabels.contactName" width="120" />
        <el-table-column prop="contactPhone" :label="ownerFieldLabels.contactPhone" width="140" />
        <el-table-column prop="creditCode" :label="ownerFieldLabels.creditCode" width="200" />
        <el-table-column :label="ownerFieldLabels.status" width="110">
          <template #default="{ row }">
            <StatusTag :label="ownerStatusLabels[row.status]" :type="ownerStatusTags[row.status]" />
          </template>
        </el-table-column>
        <el-table-column
          prop="updateTime"
          :label="ownerFieldLabels.updateTime"
          width="160"
          sortable="custom"
        >
          <template #default="{ row }">{{ formatListTime(row.updateTime) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="90" fixed="right">
          <template #default="{ row }">
            <ActionButton variant="text" @click.stop="openDetail(row.ownerNo)">详情</ActionButton>
          </template>
        </el-table-column>
      </DataTable>

      <!-- UI-S02、UI-W10：空态三段，无档案与筛选过窄两套文案，各自给可执行出口 -->
      <el-empty v-else-if="!error" :description="filtered ? '暂无数据' : '暂无货主档案'">
        <p class="owner-empty__hint">
          {{ filtered ? '当前筛选条件下没有记录' : '尚未建立任何货主档案' }}
        </p>
        <ActionButton v-if="filtered" variant="secondary" @click="onReset">重置</ActionButton>
        <ActionButton v-else-if="canWrite && allOwners" variant="primary" @click="goCreate">新建</ActionButton>
      </el-empty>
    </template>
  </el-card>

  <!-- UI-C04 + UI-D01~UI-D13：列表页详情唯一外壳，三段分区、两列、宽 880 -->
  <DetailDrawer
    v-model="detailVisible"
    :title="detail?.ownerName ?? ''"
    :subtitle="detail ? `货主编号 ${detail.ownerNo}` : undefined"
    :status="detailStatusLabel"
    :status-type="detailStatusType"
    :sections="detailSections"
    :columns="2"
    :width="880"
    :loading="detailLoading"
    :error="detailError"
    @retry="retryDetail"
  >
    <template #footer>
      <template v-if="canWrite && detail">
        <template v-if="detail.status === 'ACTIVE'">
          <!-- 可用态：「停用」危险色靠左，与主操作间隔 ≥24（UI-C07、UI-D07） -->
          <span class="owner-actions__danger">
            <ActionButton variant="danger" @click="openStatusDialog('disable')">停用</ActionButton>
          </span>
          <ActionButton variant="primary" @click="goEdit">编辑</ActionButton>
        </template>
        <template v-else>
          <!-- 停用态：「编辑」置灰并给原因（UI-I03），主操作为「启用」 -->
          <ActionButton variant="secondary" reason="停用状态不可改档，需先启用" @click="goEdit">
            编辑
          </ActionButton>
          <ActionButton variant="primary" @click="openStatusDialog('enable')">启用</ActionButton>
        </template>
      </template>
    </template>
  </DetailDrawer>

  <!-- UI-C05：停用与启用共用一个弹窗外壳，只换动作词、影响清单与接口 -->
  <ImpactDialog
    ref="dialogRef"
    v-model="statusDialog.visible"
    :title="dialogTitle"
    :subject="dialogSubject"
    :impacts="dialogImpacts"
    :reason-label="statusDialog.action === 'disable' ? '停用原因' : '启用原因'"
    :confirm-text="dialogConfirmText"
    :danger="statusDialog.action === 'disable'"
    :loading="statusDialog.submitting"
    @confirm="onConfirmStatus"
  />
</template>

<style scoped>
/* UI-W10：空态次文案与主文案同一屏，辅助色，13 档（UI-T12） */
.owner-empty__hint {
  margin: 0 0 16px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
/* UI-B08：货主编号完整展示，超宽中间省略、悬浮给全号；点击与行点击同级 */
.owner-no {
  display: block;
  max-width: 100%;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  color: var(--el-color-primary);
  cursor: pointer;
}
/* UI-S03：整页无权访问的说明行，与 ForbiddenView 同一写法 */
.owner-block__line {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
/*
 * UI-C07、UI-D07：危险操作与主操作间距不小于 24。
 * 相邻按钮的 12px 由 ActionButton 的相邻兄弟规则承担，这里补到 24，不叠加成 36。
 */
.owner-actions__danger {
  margin-right: 12px;
}
</style>
