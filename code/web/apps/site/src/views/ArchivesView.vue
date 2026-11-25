<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
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
  StatusTag,
  parseUtcIso,
  toUtcIso,
} from '@wms/design'
import {
  archiveChangeFields,
  archiveFieldLabels,
  archiveSortOptions,
  archiveStatusLabels,
  archiveStatusTags,
  changeRequestFieldLabels,
  sortDirOptions,
} from '@wms/dict'
import {
  describeApiError,
  newIdempotencyKey,
  type ArchiveChangeField,
  type ArchiveItem,
  type ArchiveSortBy,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useSessionStore } from '../stores/session'
import { buildArchiveSections, toAcceptancePoll, useSiteArchiveApi } from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 单仓端本仓生效档案（`UI-P01` 列表页 + `UI-D01` 详情抽屉 + `UI-C31` 操作抽屉）。
 *
 * 查询走 API-010，只取本仓生效档案；主数据字段只读，字段变更只能到变更申请（API-011），
 * 受理结果由 API-017 轮询取得（`UI-F02`），前端不判定业务结果（`UI-I11`）。
 * 降级数据按 `UI-S05` 显示数据时间戳，提示条常驻到刷新成功（`UI-S07`），不伪装成实时数据。
 * 会话令牌、仓标识与权限列表由 API-018 换取、API-019 续期，本页只用不换。
 */
const session = useSessionStore()
const api = useSiteArchiveApi()
const router = useRouter()
/** API-017：受理结果查询（`AcceptPanel` 按 `UI-F02` 轮询，超时保留受理号）。 */
const pollAcceptance = toAcceptancePoll(api)

/** 查询条件与排序按 `UI-X04` 写进地址栏；筛选超过 4 项时折叠状态也随地址栏保留（`UI-C02`）。 */
const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'ownerCode',
  'name',
  'category',
  'updatedFrom',
  'updatedTo',
  'sortBy',
  'sortDir',
  'filtersCollapsed',
] as const)

const items = ref<ArchiveItem[]>([])
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
/** 降级数据时间戳：存在即说明本次结果来自缓存（契约「降级约定」）。 */
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

const changeVisible = ref(false)
const changeTarget = ref<ArchiveItem | null>(null)
const changeForm = reactive({ changeField: '', targetValue: '', reason: '' })
const changeError = ref('')
const submitting = ref(false)
const submitKey = ref('')

/**
 * 同一档案待审申请的前置判定结果（`ui.md`「单仓端 发起变更申请」）。
 * 数据来自 API-014 的 `archiveId` 过滤，只用于置灰与给原因；是否允许仍由服务端判定（`STATE_INVALID`）。
 */
const pendingByArchive = ref<Record<string, boolean>>({})

const canChange = computed(() => session.hasPermission(PERMISSIONS.archiveSiteChange))
const detailSections = computed(() => (detailRow.value ? buildArchiveSections(detailRow.value) : []))
/** UI-I03：前置不满足时置灰并给一句原因。 */
const pendingReason = computed(() =>
  detailRow.value && pendingByArchive.value[detailRow.value.archiveId] ? '该档案已有待审申请' : '',
)
/** UI-S07：同页多个降级数据源只显示一条，提示条取最早的数据时间。 */
const degradeSources = computed(() => [dataTimestamp.value, acceptTimestamp.value])

/**
 * 更新时间范围：控件用界面时间，地址栏与查询串用 UTC 时间戳（契约「时间与金额口径」），
 * 转换只在 `@wms/design` 的 `format.ts` 做一次（`UI-W13`）。
 */
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

/** UI-C02：折叠状态随地址栏保留，展开后仍保留已填条件。 */
const filtersCollapsed = computed(() => filter.filtersCollapsed === '1')
function toggleFilters(collapsed: boolean) {
  filter.filtersCollapsed = collapsed ? '1' : ''
  sync()
}

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.list({
      ownerCode: filter.ownerCode,
      name: filter.name,
      category: filter.category,
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

/** 打开详情时按 API-014 判定该档案是否已有待审申请（`UI-D11`：就地给出口，不整块白屏）。 */
function openDetail(row: ArchiveItem) {
  detailRow.value = row
  detailVisible.value = true
  void checkPending(row.archiveId)
}

/**
 * 按 `archiveId` + `PENDING` 取一条即可判断（API-014）。
 * 判定失败时不改按钮状态：服务端仍会按状态机拒绝，前端不做业务判定（`UI-I11`）。
 */
async function checkPending(archiveId: string) {
  if (pendingByArchive.value[archiveId] !== undefined) {
    return
  }
  try {
    const data = await api.listChangeRequests({ archiveId, status: 'PENDING', limit: 1 })
    pendingByArchive.value = { ...pendingByArchive.value, [archiveId]: data.items.length > 0 }
  } catch {
    // 前置判定不可用：保持入口可点，由服务端返回 STATE_INVALID。
  }
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

/** UI-F03：受理结果到达后重取列表，并重新判定当前档案的待审状态。 */
function onSettled() {
  pendingByArchive.value = {}
  const target = changeTarget.value
  if (target) {
    void checkPending(target.archiveId)
  }
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

/** 发起变更：先关详情再开操作抽屉（`UI-D09`），不直接改主数据字段。 */
function openChange(row: ArchiveItem) {
  detailVisible.value = false
  changeTarget.value = row
  changeForm.changeField = ''
  changeForm.targetValue = ''
  changeForm.reason = ''
  changeError.value = ''
  submitKey.value = ''
  changeVisible.value = true
}

/** 只做必填与长度校验；是否允许发起由服务端判定（`STATE_INVALID`）。 */
function validateChange(): boolean {
  if (!changeForm.changeField) {
    changeError.value = '变更字段必填'
    return false
  }
  if (!changeForm.targetValue.trim()) {
    changeError.value = '目标值必填'
    return false
  }
  if (!changeForm.reason.trim()) {
    changeError.value = '原因必填'
    return false
  }
  return true
}

/** API-011：只拿受理号与受理时状态快照，生效由运营审核通过后写回（D20）。 */
async function submitChange() {
  const row = changeTarget.value
  if (!row || submitting.value) {
    return
  }
  changeError.value = ''
  if (!validateChange()) {
    return
  }
  if (!submitKey.value) {
    submitKey.value = newIdempotencyKey()
  }
  submitting.value = true
  try {
    const ticket = await api.createChangeRequest(
      {
        archiveId: row.archiveId,
        changeField: changeForm.changeField as ArchiveChangeField,
        targetValue: changeForm.targetValue.trim(),
        reason: changeForm.reason.trim(),
      },
      submitKey.value,
    )
    acceptNo.value = ticket.acceptNo
    changeVisible.value = false
  } catch (e) {
    changeError.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

function goChangeRequests() {
  void router.push('/site/archive-change-requests')
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>商品档案</span>
        <!-- UI-W01：重新取数用「刷新」；降级数据的出口在提示条上（UI-S05） -->
        <ActionButton variant="secondary" @click="load">刷新</ActionButton>
      </div>
    </template>

    <!-- UI-S05、UI-S07：降级数据顶部提示条常驻，显示最早的数据时间，同页只出一条 -->
    <DegradedBanner
      :sources="degradeSources"
      hint="当前显示本仓缓存数据，待刷新为实时结果。"
      @refresh="refreshDegraded"
    />

    <!-- UI-F01~UI-F03：写入只到受理号，结果由 API-017 轮询替换 -->
    <AcceptPanel
      ref="acceptPanel"
      :accept-no="acceptNo"
      :poll="pollAcceptance"
      degrade-notice-in-page
      @refresh="load"
      @settled="onSettled"
      @degrade="onAcceptDegrade"
    />
    <div v-if="acceptNo" class="accept-next">
      <ActionButton variant="text" @click="goChangeRequests">查看申请进度</ActionButton>
    </div>

    <!-- UI-C02：筛选超过 4 项折叠，折叠状态随地址栏保留；UI-X04：排序一并入地址栏 -->
    <FilterBar
      :collapsed="filtersCollapsed"
      @update:collapsed="toggleFilters"
      @search="onSearch"
      @reset="onReset"
    >
      <FormInput v-model="filter.ownerCode" placeholder="货主系统编码" style="width: 200px" />
      <FormInput v-model="filter.name" placeholder="名称" style="width: 200px" />
      <FormInput v-model="filter.category" placeholder="品类" style="width: 180px" />
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

    <DataTable
      v-if="loading || items.length > 0"
      :data="items"
      :loading="loading"
      :has-more="hasMore"
      :has-prev="canPrev"
      @next="onNext"
      @prev="onPrev"
      @row-click="openDetail"
    >
      <el-table-column prop="ownerCode" :label="archiveFieldLabels.ownerCode" min-width="200" />
      <el-table-column prop="name" :label="archiveFieldLabels.name" min-width="200" />
      <el-table-column prop="category" :label="archiveFieldLabels.category" min-width="160" />
      <el-table-column prop="version" :label="archiveFieldLabels.version" width="110" />
      <el-table-column :label="archiveFieldLabels.status" width="120">
        <template #default="{ row }">
          <StatusTag :label="archiveStatusLabels[row.status]" :type="archiveStatusTags[row.status]" />
        </template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <ActionButton variant="text" @click.stop="openDetail(row)">详情</ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <el-empty v-else :description="error ? '数据未加载成功' : '本仓暂无生效档案'">
      <p class="empty-hint">{{ error ? '请按上方提示重试' : '当前筛选条件下没有记录' }}</p>
      <ActionButton variant="secondary" @click="onReset">重置</ActionButton>
    </el-empty>

    <DetailDrawer
      v-model="detailVisible"
      :title="detailRow?.name ?? ''"
      :subtitle="detailRow ? `档案编号 ${detailRow.archiveId}` : undefined"
      :status="detailRow ? archiveStatusLabels[detailRow.status] : undefined"
      :status-type="detailRow ? archiveStatusTags[detailRow.status] : undefined"
      :sections="detailSections"
      :columns="1"
      :width="880"
    >
      <template #footer>
        <!-- 缺 archive.site.change 时不渲染入口（UI-X06）；待审前置按 UI-I03 置灰给原因 -->
        <ActionButton
          v-if="canChange && detailRow"
          variant="primary"
          :reason="pendingReason"
          @click="openChange(detailRow)"
        >
          发起变更
        </ActionButton>
      </template>
    </DetailDrawer>

    <ActionDrawer
      v-model="changeVisible"
      :title="`发起变更 · ${changeTarget?.name ?? ''}`"
      :width="480"
      :closable="!submitting"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="主数据字段不直接修改：提交后由运营审核，通过后写回档案。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="100px">
        <FormSection title="变更内容">
          <FormField :label="changeRequestFieldLabels.changeField" required>
            <el-select v-model="changeForm.changeField" placeholder="选择变更字段" style="width: 100%">
              <el-option v-for="item in archiveChangeFields" :key="item.code" :label="item.label" :value="item.code" />
            </el-select>
          </FormField>
          <FormField label="目标值" required>
            <FormInput v-model="changeForm.targetValue" placeholder="变更后的值" />
          </FormField>
          <FormField label="原因" required>
            <el-input v-model="changeForm.reason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="写明变更原因" />
          </FormField>
        </FormSection>
      </el-form>
      <el-alert v-if="changeError" :title="changeError" type="error" :closable="false" show-icon />
      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="changeVisible = false">取消</ActionButton>
        <ActionButton variant="primary" :loading="submitting" @click="submitChange">提交</ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.accept-next {
  margin: -8px 0 16px;
}
.empty-hint {
  margin: 0 0 16px;
  font-size: 16px;
  color: var(--el-text-color-secondary);
}
</style>
