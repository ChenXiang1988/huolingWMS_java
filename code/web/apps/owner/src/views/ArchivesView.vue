<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
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
  sortDirOptions,
} from '@wms/dict'
import {
  ApiError,
  describeApiError,
  newIdempotencyKey,
  type ArchiveItem,
  type ArchiveSortBy,
  type ArchiveStatus,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useSessionStore } from '../stores/session'
import { buildArchiveSections, toAcceptancePoll, useOwnerArchiveApi } from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 货主端商品档案（`UI-P01` 列表页 + `UI-C31` 操作抽屉）。
 *
 * 只操作自有档案：数据范围由服务端按归属货主注入，前端不传归属（`design.md`「鉴权」）。
 * 新建走 API-001、编辑走 API-003、提交走 API-002、停用（逻辑删除）走 API-004，全部只返回受理号与
 * 受理时状态快照（D20），结果由 API-015 每 3 秒轮询取得（`UI-F02`），页面不判定业务结果（`UI-I11`）。
 * 界面上不出现库位号与单仓部署信息（D17、`UI-P05`）。
 */
const session = useSessionStore()
const api = useOwnerArchiveApi()
/** API-015：受理结果查询（`AcceptPanel` 按 `UI-F02` 轮询，超时保留受理号）。 */
const pollAcceptance = toAcceptancePoll(api)

/** 查询条件与排序按 `UI-X04` 写进地址栏；筛选超过 4 项时折叠状态也随地址栏保留（`UI-C02`）。 */
const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'ownerCode',
  'name',
  'status',
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

const formVisible = ref(false)
const formMode = ref<'create' | 'edit'>('create')
const editing = ref<ArchiveItem | null>(null)
const submitting = ref(false)
const formError = ref('')
/** UI-X12：版本冲突时不静默覆盖，给「刷新查看」与「放弃本次修改」两个出口。 */
const conflict = ref(false)
const submitKey = ref('')
const form = reactive({
  ownerCode: '',
  name: '',
  barcode: '',
  barcodeType: '',
  baseUnit: '',
  storageCondition: '',
  category: '',
  spec: '',
  brand: '',
  remark: '',
})
const errors = reactive<Record<string, string>>({})

const disableVisible = ref(false)
const disableTarget = ref<ArchiveItem | null>(null)
const disableReason = ref('')
const disableConfirmName = ref('')
const disableError = ref('')

const canWrite = computed(() => session.hasPermission(PERMISSIONS.archiveOwnerWrite))
const detailSections = computed(() => (detailRow.value ? buildArchiveSections(detailRow.value) : []))
/** UI-S07：同页多个降级数据源只显示一条，提示条取最早的数据时间。 */
const degradeSources = computed(() => [dataTimestamp.value, acceptTimestamp.value])

/**
 * 更新时间范围：控件用界面时间，地址栏与查询串用 UTC 时间戳（契约「时间与金额口径」），
 * 转换只在 `@wms/design` 的 `format.ts` 做一次（`UI-W13`）。地址栏保持两个独立参数，
 * 与服务端 `updatedFrom`、`updatedTo` 一一对应。
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
/** 草稿与驳回态可编辑（design「页面与接口映射」）。 */
const editable = (row: ArchiveItem) => row.status === 'DRAFT' || row.status === 'REJECTED'
/** 草稿、驳回态提交审核；停用态提交恢复（`22` 状态机允许的迁移）。 */
const submittable = (row: ArchiveItem) => row.status === 'DRAFT' || row.status === 'REJECTED' || row.status === 'DISABLED'
/** 启用态才可停用；停用只做逻辑停用，档案与历史流水保留（INV-18）。 */
const disableable = (row: ArchiveItem) => row.status === 'ACTIVE'

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.list({
      ownerCode: filter.ownerCode,
      name: filter.name,
      status: filter.status ? (filter.status as ArchiveStatus) : '',
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

function openDetail(row: ArchiveItem) {
  detailRow.value = row
  detailVisible.value = true
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

/** UI-F03：受理结果到达后按新结果重取当前列表（UI-X11：刷新只作用于当前区域）。 */
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

function resetForm() {
  Object.assign(form, {
    ownerCode: '',
    name: '',
    barcode: '',
    barcodeType: '',
    baseUnit: '',
    storageCondition: '',
    category: '',
    spec: '',
    brand: '',
    remark: '',
  })
  Object.keys(errors).forEach((key) => delete errors[key])
  formError.value = ''
  conflict.value = false
  submitKey.value = ''
}

/** 新建：详情先关，再开操作抽屉（`UI-D09`、`UI-D01`）。 */
function openCreate() {
  detailVisible.value = false
  resetForm()
  formMode.value = 'create'
  editing.value = null
  formVisible.value = true
}

function openEdit(row: ArchiveItem) {
  detailVisible.value = false
  resetForm()
  formMode.value = 'edit'
  editing.value = row
  Object.assign(form, {
    ownerCode: row.ownerCode,
    name: row.name,
    barcode: row.barcode,
    barcodeType: row.barcodeType ?? '',
    baseUnit: row.baseUnit,
    storageCondition: row.storageCondition,
    category: row.category ?? '',
    spec: row.spec ?? '',
    brand: row.brand ?? '',
    remark: row.remark ?? '',
  })
  formVisible.value = true
}

/** 只做必填与长度校验；判重与状态由服务端判定（design「表单与校验」）。 */
function validate(): boolean {
  const found: Record<string, string> = {}
  if (!form.ownerCode.trim()) {
    found.ownerCode = '必填'
  }
  if (!form.name.trim()) {
    found.name = '必填'
  }
  if (!form.barcode.trim()) {
    found.barcode = '必填'
  }
  if (!form.baseUnit.trim()) {
    found.baseUnit = '必填'
  }
  if (!form.storageCondition.trim()) {
    found.storageCondition = '必填'
  }
  if (form.remark.trim().length > 200) {
    found.remark = '最多 200 个字'
  }
  Object.keys(errors).forEach((key) => delete errors[key])
  Object.assign(errors, found)
  return Object.keys(found).length === 0
}

function writeBody() {
  return {
    ownerCode: form.ownerCode.trim(),
    name: form.name.trim(),
    barcode: form.barcode.trim(),
    baseUnit: form.baseUnit.trim(),
    storageCondition: form.storageCondition.trim(),
    barcodeType: form.barcodeType.trim() || undefined,
    category: form.category.trim() || undefined,
    spec: form.spec.trim() || undefined,
    brand: form.brand.trim() || undefined,
    remark: form.remark.trim() || undefined,
  }
}

async function submitForm() {
  if (submitting.value) {
    return
  }
  formError.value = ''
  conflict.value = false
  if (!validate()) {
    formError.value = '必填项未填写，请按字段提示补齐'
    return
  }
  if (!submitKey.value) {
    submitKey.value = newIdempotencyKey()
  }
  submitting.value = true
  try {
    if (formMode.value === 'create') {
      const ticket = await api.create(writeBody(), submitKey.value)
      acceptNo.value = ticket.acceptNo
    } else {
      const row = editing.value
      if (!row) {
        return
      }
      const ticket = await api.update(row.archiveId, { ...writeBody(), version: row.version }, submitKey.value)
      acceptNo.value = ticket.acceptNo
    }
    formVisible.value = false
    await load()
  } catch (e) {
    formError.value = describeApiError(e)
    conflict.value = e instanceof ApiError && e.code === 'CONFLICT_VERSION'
  } finally {
    submitting.value = false
  }
}

/** UI-X12「刷新查看」：重取该档案再回到编辑，保留出口但不覆盖他人结果。 */
async function refreshAfterConflict() {
  const row = editing.value
  if (!row) {
    return
  }
  await load()
  const fresh = items.value.find((item) => item.archiveId === row.archiveId)
  formVisible.value = false
  conflict.value = false
  formError.value = ''
  if (fresh) {
    openEdit(fresh)
  }
}

function openDisable(row: ArchiveItem) {
  detailVisible.value = false
  disableTarget.value = row
  disableReason.value = ''
  disableConfirmName.value = ''
  disableError.value = ''
  disableVisible.value = true
}

/** API-004：停用（逻辑删除）。原因必填，且需输入档案名称二次确认（`UI-F05`）。 */
async function submitDisable() {
  const row = disableTarget.value
  if (!row || submitting.value) {
    return
  }
  const reason = disableReason.value.trim()
  if (!reason) {
    disableError.value = '停用原因必填'
    return
  }
  if (disableConfirmName.value.trim() !== row.name) {
    disableError.value = '请输入完整档案名称以确认'
    return
  }
  submitting.value = true
  disableError.value = ''
  try {
    const ticket = await api.disable(row.archiveId, { reason, version: row.version }, newIdempotencyKey())
    acceptNo.value = ticket.acceptNo
    disableVisible.value = false
    await load()
  } catch (e) {
    disableError.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

/** API-002：提交审核（含停用态提交恢复）。 */
async function submitForReview(row: ArchiveItem) {
  if (submitting.value) {
    return
  }
  submitting.value = true
  error.value = ''
  try {
    const ticket = await api.submit(row.archiveId, { version: row.version }, newIdempotencyKey())
    acceptNo.value = ticket.acceptNo
    await load()
  } catch (e) {
    error.value = describeApiError(e)
    // 状态已变化时刷新列表，按当前状态重新操作（design「提交按钮」失败表现）
    await load()
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>商品档案</span>
        <!-- UI-X06：无写权限时不渲染入口，不置灰 -->
        <ActionButton v-if="canWrite" variant="primary" @click="openCreate">新建</ActionButton>
      </div>
    </template>

    <!-- UI-S05、UI-S07：降级数据只出一条提示条，显示最早的数据时间与「刷新取实时」出口 -->
    <DegradedBanner :sources="degradeSources" @refresh="refreshDegraded" />

    <!-- UI-F01~UI-F03：写入只到受理号，结果由 API-015 轮询替换，超时保留受理号（UI-F02） -->
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
      <FormInput v-model="filter.ownerCode" placeholder="货主系统编码" style="width: 180px" />
      <FormInput v-model="filter.name" placeholder="名称" style="width: 180px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 140px">
        <el-option
          v-for="status in archiveStatusOptions"
          :key="status"
          :label="archiveStatusLabels[status]"
          :value="status"
        />
      </el-select>
      <FormInput v-model="filter.category" placeholder="品类" style="width: 160px" />
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
      <el-table-column prop="ownerCode" :label="archiveFieldLabels.ownerCode" min-width="180" />
      <el-table-column prop="name" :label="archiveFieldLabels.name" min-width="180" />
      <el-table-column :label="archiveFieldLabels.status" width="110">
        <template #default="{ row }">
          <StatusTag :label="archiveStatusLabels[row.status]" :type="archiveStatusTags[row.status]" />
        </template>
      </el-table-column>
      <el-table-column prop="version" :label="archiveFieldLabels.version" width="90" />
      <el-table-column :label="archiveFieldLabels.updatedAt" width="170">
        <template #default="{ row }">{{ formatListTime(row.updatedAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="220" fixed="right">
        <template #default="{ row }">
          <ActionButton variant="text" @click.stop="openDetail(row)">详情</ActionButton>
          <ActionButton v-if="canWrite && editable(row)" variant="text" @click.stop="openEdit(row)">编辑</ActionButton>
          <ActionButton v-if="canWrite && submittable(row)" variant="text" @click.stop="submitForReview(row)">
            提交
          </ActionButton>
          <ActionButton v-if="canWrite && disableable(row)" variant="text" @click.stop="openDisable(row)">
            停用
          </ActionButton>
        </template>
      </el-table-column>
    </DataTable>

    <el-empty v-else :description="error ? '数据未加载成功' : '暂无商品档案'">
      <p class="empty-hint">{{ error ? '请按上方提示重试' : '当前筛选条件下没有记录' }}</p>
      <ActionButton variant="secondary" @click="onReset">重置</ActionButton>
    </el-empty>

    <DetailDrawer
      v-model="detailVisible"
      :title="detailRow?.name ?? ''"
      :subtitle="detailRow ? `货主系统编码 ${detailRow.ownerCode}` : undefined"
      :status="detailRow ? archiveStatusLabels[detailRow.status] : undefined"
      :status-type="detailRow ? archiveStatusTags[detailRow.status] : undefined"
      :sections="detailSections"
      :columns="2"
      :width="880"
    />

    <ActionDrawer
      v-model="formVisible"
      :title="`${formMode === 'create' ? '新建' : '编辑'} · ${formMode === 'create' ? '商品档案' : (editing?.name ?? '')}`"
      :width="720"
      :closable="!submitting"
    >
      <el-form label-width="120px">
        <FormSection title="必填信息">
          <FormField :label="archiveFieldLabels.ownerCode" required :error="errors.ownerCode">
            <FormInput v-model="form.ownerCode" placeholder="货主自己系统里的编码，货主内唯一" />
          </FormField>
          <FormField :label="archiveFieldLabels.name" required :error="errors.name">
            <FormInput v-model="form.name" placeholder="商品名称" />
          </FormField>
          <FormField :label="archiveFieldLabels.barcode" required :error="errors.barcode">
            <FormInput v-model="form.barcode" placeholder="条码，货主内唯一" />
          </FormField>
          <FormField :label="archiveFieldLabels.baseUnit" required :error="errors.baseUnit">
            <FormInput v-model="form.baseUnit" placeholder="库存计量最小单位" />
          </FormField>
          <FormField :label="archiveFieldLabels.storageCondition" required :error="errors.storageCondition">
            <FormInput v-model="form.storageCondition" placeholder="保管条件" />
          </FormField>
        </FormSection>

        <FormSection title="附属信息">
          <FormField :label="archiveFieldLabels.barcodeType" :error="errors.barcodeType">
            <FormInput v-model="form.barcodeType" placeholder="条码类型" />
          </FormField>
          <FormField :label="archiveFieldLabels.category" :error="errors.category">
            <FormInput v-model="form.category" placeholder="品类" />
          </FormField>
          <FormField :label="archiveFieldLabels.spec" :error="errors.spec">
            <FormInput v-model="form.spec" placeholder="规格型号" />
          </FormField>
          <FormField :label="archiveFieldLabels.brand" :error="errors.brand">
            <FormInput v-model="form.brand" placeholder="品牌" />
          </FormField>
          <FormField :label="archiveFieldLabels.remark" :error="errors.remark">
            <el-input v-model="form.remark" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="备注" />
          </FormField>
        </FormSection>
      </el-form>

      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />
      <!-- UI-X12：版本冲突两个出口，不静默重试 -->
      <div v-if="conflict" class="conflict-actions">
        <ActionButton variant="secondary" @click="refreshAfterConflict">刷新查看</ActionButton>
        <ActionButton variant="secondary" @click="formVisible = false">放弃本次修改</ActionButton>
      </div>

      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="formVisible = false">取消</ActionButton>
        <ActionButton variant="primary" :loading="submitting" @click="submitForm">保存</ActionButton>
      </template>
    </ActionDrawer>

    <ActionDrawer
      v-model="disableVisible"
      :title="`停用 · ${disableTarget?.name ?? ''}`"
      :width="480"
      :closable="!submitting"
    >
      <el-alert
        type="warning"
        :closable="false"
        show-icon
        title="停用后不再接新单，档案与历史流水保留，可按系统编号追溯。"
        style="margin-bottom: 16px"
      />
      <el-form label-width="100px">
        <FormSection title="停用确认">
          <FormField :label="archiveFieldLabels.disableReason" required :error="disableError">
            <el-input v-model="disableReason" type="textarea" :rows="3" maxlength="200" show-word-limit placeholder="写明停用原因" />
          </FormField>
          <FormField label="确认名称" required>
            <FormInput v-model="disableConfirmName" :placeholder="`请输入「${disableTarget?.name ?? ''}」以确认`" />
          </FormField>
        </FormSection>
      </el-form>
      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="disableVisible = false">取消</ActionButton>
        <!-- UI-F05：危险操作二次确认；UI-I05：提交中禁用 -->
        <ActionButton variant="danger" :loading="submitting" @click="submitDisable">停用</ActionButton>
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
.conflict-actions {
  margin-top: 16px;
}
.empty-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
