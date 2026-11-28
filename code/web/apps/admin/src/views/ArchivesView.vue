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
  OwnerSelect,
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
  sortDirOptions,
} from '@wms/dict'
import {
  describeApiError,
  newIdempotencyKey,
  type ArchiveItem,
  type ArchiveSortBy,
  type ArchiveStatus,
  type HubCreateBody,
  type SortDir,
} from '@wms/request'
import { PERMISSIONS } from '../permissions'
import { useAuthStore } from '../stores/auth'
import {
  buildArchiveSections,
  toAcceptancePoll,
  useHubArchiveApi,
  useOwnerOptionLoader,
} from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 运营端全量商品档案（`UI-P01` 列表页骨架）。
 * 查询走 API-007，代建走 API-006、归属由 API-020 货主选择器给出；
 * 写入只返回受理号与受理时状态快照（D20），结果由 API-016 每 3 秒轮询取得（`UI-F02`）。
 * 页面不做业务判定：状态是否允许操作、归属是否越权都看服务端返回（`UI-I11`）。
 */
const auth = useAuthStore()
const api = useHubArchiveApi()
/** API-020：共享货主选择器的数据源（参数已按契约改造为 `ownerNo`／`ownerName`）。 */
const loadOwners = useOwnerOptionLoader()
/** API-016：受理结果查询（`AcceptPanel` 按 `UI-F02` 轮询，超时保留受理号）。 */
const pollAcceptance = toAcceptancePoll(api)

/** 查询条件、排序与游标进地址栏（`UI-X04`）；筛选超过 4 项时折叠状态一并保留（`UI-C02`）。 */
const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'ownerId',
  'ownerCode',
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

const createVisible = ref(false)
const submitting = ref(false)
const formError = ref('')
/** 同一次代建重提沿用首次的幂等键，避免生成第二份草稿（契约「幂等约定」）。 */
const submitKey = ref('')
const form = reactive({
  ownerId: '',
  ownerCode: '',
  name: '',
  barcode: '',
  barcodeType: '',
  baseUnit: '',
  storageCondition: '',
  category: '',
  spec: '',
  brand: '',
})
const errors = reactive<Record<string, string>>({})

const canCreate = computed(() => auth.hasPermission(PERMISSIONS.archiveHubCreate))

const detailSections = computed(() => (detailRow.value ? buildArchiveSections(detailRow.value) : []))
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

async function load() {
  loading.value = true
  error.value = ''
  try {
    const data = await api.list({
      ownerId: filter.ownerId,
      ownerCode: filter.ownerCode,
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

/** 列表刷新后把抽屉绑定的档案换成本次拉取的对象，版本号与状态随之更新（`UI-D10`）。 */
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

/**
 * 代建入口（`UI-P01` 页头主操作）。
 * 详情抽屉打开时先关详情再开操作抽屉，抽屉内不套抽屉（`UI-D09`）。
 */
function openCreate() {
  detailVisible.value = false
  formError.value = ''
  Object.keys(errors).forEach((key) => delete errors[key])
  submitKey.value = ''
  createVisible.value = true
}

/** 选中货主后清掉「必填」提示：归属只校验是否已选，存在性由服务端判定（`UI-I11`）。 */
function onOwnerSelected() {
  if (errors.ownerId) {
    delete errors.ownerId
  }
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

/** 只做必填、长度与格式校验；判重与状态迁移由服务端返回（design「表单与校验」）。 */
function validate(): boolean {
  const found: Record<string, string> = {}
  // 归属由 API-020 选择器给出，只校验「是否已选」（ui.md「归属未指定禁止提交」）。
  if (!form.ownerId) {
    found.ownerId = '必填'
  }
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
  Object.keys(errors).forEach((key) => delete errors[key])
  Object.assign(errors, found)
  return Object.keys(found).length === 0
}

async function submitCreate() {
  if (submitting.value) {
    return
  }
  formError.value = ''
  if (!validate()) {
    formError.value = '必填项未填写，请按字段提示补齐'
    return
  }
  if (!submitKey.value) {
    submitKey.value = newIdempotencyKey()
  }
  submitting.value = true
  try {
    const body: HubCreateBody = {
      ownerId: form.ownerId,
      ownerCode: form.ownerCode.trim(),
      name: form.name.trim(),
      barcode: form.barcode.trim(),
      baseUnit: form.baseUnit.trim(),
      storageCondition: form.storageCondition.trim(),
      barcodeType: form.barcodeType.trim() || undefined,
      category: form.category.trim() || undefined,
      spec: form.spec.trim() || undefined,
      brand: form.brand.trim() || undefined,
    }
    const ticket = await api.create(body, submitKey.value)
    acceptNo.value = ticket.acceptNo
    createVisible.value = false
    await load()
  } catch (e) {
    formError.value = describeApiError(e)
  } finally {
    submitting.value = false
  }
}

onMounted(load)
</script>

<template>
  <!-- UI-P01：页头给标题与主操作，筛选区、表格与分页同容器 -->
  <PageHeader title="商品档案">
    <template #actions>
      <!-- UI-X06：无功能权限时不渲染入口，不置灰 -->
      <ActionButton v-if="canCreate" variant="primary" @click="openCreate">新建</ActionButton>
    </template>
  </PageHeader>
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

    <!-- UI-S04：接口失败保留筛选并给重试出口，不整页白屏 -->
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

    <!-- UI-S02：空数据给出口，不停在空白表格 -->
    <el-empty v-else :description="error ? '数据未加载成功' : '暂无商品档案'">
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
      :columns="2"
      :width="880"
    />

    <ActionDrawer v-model="createVisible" title="新建 · 商品档案" :width="720" :closable="!submitting">
      <el-form label-width="120px">
        <FormSection title="归属与标识">
          <FormField :label="archiveFieldLabels.ownerId" required :error="errors.ownerId">
            <!--
              API-020：共享货主选择器（`UI-B01`、`UI-B02`），归属从服务端给出的可用货主里选，
              不再手填标识；查询参数已按契约 **BREAKING** 改造为 `ownerNo`／`ownerName`。
            -->
            <OwnerSelect
              v-model="form.ownerId"
              :loader="loadOwners"
              :disabled="submitting"
              @select="onOwnerSelected"
            />
            <p class="field-help">归属创建后不可变更，运营只获得管理视图。</p>
          </FormField>
          <FormField :label="archiveFieldLabels.ownerCode" required :error="errors.ownerCode">
            <FormInput v-model="form.ownerCode" placeholder="货主自己系统里的编码，货主内唯一" />
          </FormField>
          <FormField :label="archiveFieldLabels.barcode" required :error="errors.barcode">
            <FormInput v-model="form.barcode" placeholder="条码，货主内唯一" />
          </FormField>
          <FormField :label="archiveFieldLabels.barcodeType" :error="errors.barcodeType">
            <FormInput v-model="form.barcodeType" placeholder="条码类型" />
          </FormField>
        </FormSection>

        <FormSection title="基础信息">
          <FormField :label="archiveFieldLabels.name" required :error="errors.name">
            <FormInput v-model="form.name" placeholder="商品名称" />
          </FormField>
          <FormField :label="archiveFieldLabels.baseUnit" required :error="errors.baseUnit">
            <FormInput v-model="form.baseUnit" placeholder="库存计量最小单位" />
          </FormField>
          <FormField :label="archiveFieldLabels.storageCondition" required :error="errors.storageCondition">
            <FormInput v-model="form.storageCondition" placeholder="保管条件" />
          </FormField>
          <FormField :label="archiveFieldLabels.category" :error="errors.category">
            <FormInput v-model="form.category" placeholder="品类" />
          </FormField>
        </FormSection>

        <FormSection title="附属信息">
          <FormField :label="archiveFieldLabels.spec" :error="errors.spec">
            <FormInput v-model="form.spec" placeholder="规格型号" />
          </FormField>
          <FormField :label="archiveFieldLabels.brand" :error="errors.brand">
            <FormInput v-model="form.brand" placeholder="品牌" />
          </FormField>
        </FormSection>
      </el-form>

      <el-alert v-if="formError" :title="formError" type="error" :closable="false" show-icon />

      <template #footer>
        <ActionButton variant="secondary" :disabled="submitting" @click="createVisible = false">取消</ActionButton>
        <!-- UI-I03：归属未指定时置灰并给原因；UI-I05：提交中禁用 -->
        <ActionButton
          variant="primary"
          :loading="submitting"
          :reason="form.ownerId ? '' : '归属货主未指定'"
          @click="submitCreate"
        >
          提交
        </ActionButton>
      </template>
    </ActionDrawer>
  </el-card>
</template>

<style scoped>
/* UI-C03：帮助文案在控件下方 4px，辅助色，字号取 UI-T12 最小档 */
.field-help {
  flex: 0 0 100%;
  margin: 4px 0 0;
  font-size: 12px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
/* UI-W10：空态三段，主文案由 el-empty 承担，次文案与出口在此 */
.empty-hint {
  margin: 0 0 16px;
  font-size: 13px;
  color: var(--el-text-color-secondary);
}
</style>
