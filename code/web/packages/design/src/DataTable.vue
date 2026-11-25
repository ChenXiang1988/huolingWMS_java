<script setup lang="ts">
import { ref } from 'vue'
import ActionButton from './ActionButton.vue'

/**
 * 列表表格外壳，对应 12 的 `UI-C01`、`UI-C08`：
 * - 两种分页形态都在这里收口，页面不各写一套：
 *   · 页码分页：传 `total` + `page`，用 Element Plus 分页器；
 *   · 键集分页：契约「分页约定」只允许 `cursor`／`limit`，服务端不返回总数，故不渲染页码，
 *     改用「上一页／下一页」推进游标，已加载条数常显。
 * - 表格列仍由调用方以 `el-table-column` 传入，本组件不改列结构。
 */
const props = withDefaults(
  defineProps<{
    data: unknown[]
    loading?: boolean
    /** 页码分页的总数；给 `undefined` 且给了 `hasMore` 时按键集分页渲染。 */
    total?: number
    page?: number
    /** 每页条数（页码分页用），与 `tableSize` 不是一件事。 */
    size?: number
    /** 表格密度，取 Element Plus 的 `size` 档位；运营端按 12 的数据密度用 `small`。 */
    tableSize?: 'large' | 'default' | 'small'
    /** 键集分页：是否还有下一页（契约「响应带下一页游标与是否还有数据」）。 */
    hasMore?: boolean
    /** 键集分页：当前是否可以回上一页。 */
    hasPrev?: boolean
  }>(),
  {
    loading: false,
    hasPrev: false,
  },
)

const emit = defineEmits<{
  (e: 'update:page', value: number): void
  (e: 'prev'): void
  (e: 'next'): void
}>()

/** 给了 `total` 用页码分页，否则用键集分页；两者不叠加。 */
const keyset = () => props.total === undefined && props.hasMore !== undefined

/** 内部表格实例：翻页即清空已选，由页面调用（`UI-X01`「翻页即清空已选」）。 */
const tableRef = ref<{ clearSelection?: () => void } | null>(null)

defineExpose({
  /** 清空勾选，供页面在翻页、查询、提交后调用。 */
  clearSelection: () => tableRef.value?.clearSelection?.(),
})
</script>

<template>
  <div class="data-table">
    <el-table
      ref="tableRef"
      :data="data"
      :loading="loading"
      :size="tableSize ?? 'default'"
      border
      stripe-false
      v-bind="$attrs"
    >
      <slot />
    </el-table>
    <div v-if="props.total !== undefined" class="data-table-pager">
      <el-pagination
        :current-page="page ?? 1"
        :page-size="size ?? 20"
        :total="props.total"
        :page-sizes="[20, 50, 100]"
        layout="total, sizes, prev, pager, next"
        @current-change="(value: number) => emit('update:page', value)"
      />
    </div>
    <div v-else-if="keyset()" class="data-table-pager">
      <span class="data-table-count">已加载 {{ data.length }} 条</span>
      <ActionButton variant="secondary" :disabled="!hasPrev" @click="emit('prev')">上一页</ActionButton>
      <ActionButton variant="secondary" :disabled="!hasMore" @click="emit('next')">下一页</ActionButton>
    </div>
  </div>
</template>

<style scoped>
.data-table-pager {
  display: flex;
  justify-content: flex-end;
  align-items: center;
  padding-top: 16px;
}
/* UI-T12：辅助信息取 13 档；按钮间距由 ActionButton 的相邻兄弟规则承担，此处不设 gap */
.data-table-count {
  margin-right: 12px;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
</style>
