<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { DataTable, FilterBar, FormInput, PageHeader } from '@wms/design'
import { ApiError } from '@wms/request'
import { useAuthStore } from '../stores/auth'

/**
 * API-017 条目。记录主键与操作者、目标账号同为雪花号标识，
 * 按契约「标识、类型与枚举约定」以十进制字符串接收，不转数字。
 */
interface LogItem {
  id: string
  operatorId: string
  targetAccountId: string
  action: string
  beforeJson: string | null
  afterJson: string | null
  createdAt: string
}

const auth = useAuthStore()
const items = ref<LogItem[]>([])
const total = ref(0)
const page = ref(1)
const size = ref(20)
const loading = ref(false)
const error = ref('')
const filter = reactive({ targetAccountId: '' })

async function load() {
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ page: String(page.value), size: String(size.value) })
    if (filter.targetAccountId) query.set('targetAccountId', filter.targetAccountId)
    const data = await auth.http().get<{ items: LogItem[]; total: number }>(
      `/hub/api/audit-logs?${query.toString()}`,
    )
    items.value = data.items
    total.value = data.total
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '加载失败'
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<template>
  <!-- UI-P01：页头给标题，筛选区、表格与分页同容器 -->
  <PageHeader title="审计日志" />
  <el-card>
    <FilterBar @search="load" @reset="() => { filter.targetAccountId = ''; load() }">
      <FormInput v-model="filter.targetAccountId" placeholder="目标账号 ID" style="width: 220px" />
    </FilterBar>
    <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon style="margin-bottom: 12px" />
    <DataTable :data="items" :loading="loading" :total="total" :page="page" :size="size">
      <el-table-column prop="action" label="动作" width="180" />
      <el-table-column prop="operatorId" label="操作者" width="180" />
      <el-table-column prop="targetAccountId" label="目标账号" width="180" />
      <el-table-column prop="beforeJson" label="变更前" min-width="200" />
      <el-table-column prop="afterJson" label="变更后" min-width="200" />
      <el-table-column prop="createdAt" label="时间" width="220" />
    </DataTable>
  </el-card>
</template>
