<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import {
  ActionButton,
  DataTable,
  DegradedBanner,
  DetailDrawer,
  FilterBar,
  FormInput,
  StatusTag,
  formatListTime,
} from '@wms/design'
import {
  archiveChangeFieldLabels,
  archiveSortOptions,
  changeRequestFieldLabels,
  changeRequestStatusLabels,
  changeRequestStatusTags,
  sortDirOptions,
} from '@wms/dict'
import { describeApiError, type ArchiveSortBy, type ChangeRequestItem, type ChangeRequestStatus, type SortDir } from '@wms/request'
import { buildChangeRequestSections, useSiteArchiveApi } from '../api/archive'
import { useListQuery } from '../composables/useListQuery'

/**
 * 单仓端变更申请跟踪（`UI-P01` 列表页 + `UI-D01` 详情抽屉）。
 *
 * 查询走 API-014，只返回本仓申请；按 `archiveId` 过滤可看单个档案的申请进度，
 * 档案页的待审前置判定也复用同一接口。状态与驳回原因由服务端给出，前端不推断（`UI-I11`）。
 * 降级数据按 `UI-S05` 显示数据时间戳，提示条常驻到刷新成功（`UI-S07`）。
 */
const api = useSiteArchiveApi()

/**
 * 查询条件与排序按 `UI-X04` 写进地址栏；筛选超过 4 项时折叠状态也随地址栏保留（`UI-C02`）。
 * 申请编号按契约字段名 `requestNo`（API-014「查询参数含申请编号」）。
 */
const { filter, cursor, canPrev, search, reset, next, prev, sync } = useListQuery([
  'requestNo',
  'archiveId',
  'status',
  'sortBy',
  'sortDir',
  'filtersCollapsed',
] as const)

const items = ref<ChangeRequestItem[]>([])
const hasMore = ref(false)
const nextCursor = ref<string | null>(null)
const dataTimestamp = ref<string | null>(null)
const loading = ref(false)
const error = ref('')

const detailVisible = ref(false)
const detailRow = ref<ChangeRequestItem | null>(null)

const detailSections = computed(() => (detailRow.value ? buildChangeRequestSections(detailRow.value) : []))
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
    const data = await api.listChangeRequests({
      requestNo: filter.requestNo,
      archiveId: filter.archiveId,
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

onMounted(load)
</script>

<template>
  <el-card>
    <template #header>
      <div class="page-header">
        <span>变更申请</span>
        <ActionButton variant="secondary" @click="load">刷新</ActionButton>
      </div>
    </template>

    <!-- UI-S05、UI-S07：降级数据顶部提示条常驻，显示数据时间与「刷新取实时」出口 -->
    <DegradedBanner
      :sources="[dataTimestamp]"
      hint="当前显示本仓缓存数据，待刷新为实时结果。"
      @refresh="load"
    />

    <!-- UI-C02：筛选超过 4 项折叠，折叠状态随地址栏保留；UI-X04：排序一并入地址栏 -->
    <FilterBar
      :collapsed="filtersCollapsed"
      @update:collapsed="toggleFilters"
      @search="onSearch"
      @reset="onReset"
    >
      <FormInput v-model="filter.requestNo" placeholder="申请编号" style="width: 220px" />
      <FormInput v-model="filter.archiveId" placeholder="档案编号" style="width: 220px" />
      <el-select v-model="filter.status" placeholder="状态" clearable style="width: 160px">
        <el-option v-for="(label, code) in changeRequestStatusLabels" :key="code" :label="label" :value="code" />
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
      :has-more="hasMore"
      :has-prev="canPrev"
      @next="onNext"
      @prev="onPrev"
      @row-click="openDetail"
    >
      <el-table-column prop="requestNo" :label="changeRequestFieldLabels.requestNo" min-width="200" />
      <el-table-column prop="archiveId" :label="changeRequestFieldLabels.archiveId" min-width="200" />
      <el-table-column :label="changeRequestFieldLabels.changeField" width="150">
        <template #default="{ row }">{{ archiveChangeFieldLabels[row.changeField] ?? row.changeField }}</template>
      </el-table-column>
      <el-table-column prop="targetValue" :label="changeRequestFieldLabels.targetValue" min-width="180" />
      <el-table-column :label="changeRequestFieldLabels.status" width="120">
        <template #default="{ row }">
          <StatusTag
            :label="changeRequestStatusLabels[row.status] ?? row.status"
            :type="changeRequestStatusTags[row.status] ?? 'info'"
          />
        </template>
      </el-table-column>
      <el-table-column :label="changeRequestFieldLabels.createdAt" width="180">
        <template #default="{ row }">{{ formatListTime(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
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
      :columns="1"
      :width="880"
    />
  </el-card>
</template>

<style scoped>
.page-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
}
.empty-hint {
  margin: 0 0 16px;
  font-size: 16px;
  color: var(--el-text-color-secondary);
}
</style>
