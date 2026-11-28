<script setup lang="ts">
import { ref } from 'vue'
import { keysetPageSizes } from '@wms/dict'
import ActionButton from './ActionButton.vue'

/**
 * 列表表格外壳，对应 12 的 `UI-C01`、`UI-C08`：
 * - 两种分页形态都在这里收口，页面不各写一套：
 *   · 页码分页：传 `total` + `page`，用 Element Plus 分页器；
 *   · 键集分页：契约「分页约定」只允许 `cursor`／`limit`，服务端不返回总数，故不渲染页码，
 *     改用「上一页／下一页」推进游标，已加载条数常显。
 * - 键集分页的每页条数按 `ui.md`「OA-01」给 20／50／100 三档，**不显示总数与总页数**
 *   （契约不返回总数，不得伪造）。改每页条数时由调用方重置游标，本组件只发 `update:limit`。
 * - 表格列仍由调用方以 `el-table-column` 传入，本组件不改列结构。
 * - 行高与表头高按 `UI-C01` 在本组件内收口（数据行 40、紧凑档 32、表头 44），页面不另写样式；
 *   行高用单元格 `height` 表达下限，内容换行或内嵌控件更高时自动撑开，不裁文本。
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
    /**
     * 键集分页的每页条数，取 20／50／100。
     * 只有显式传入时才渲染每页条数选择器：不传的页面保持「已加载 N 条 + 上一页／下一页」的原形态，
     * 避免出现一个点了没有效果的控件。
     */
    limit?: number
  }>(),
  {
    loading: false,
    hasPrev: false,
  },
)

const emit = defineEmits<{
  (e: 'update:page', value: number): void
  (e: 'update:limit', value: number): void
  (e: 'prev'): void
  (e: 'next'): void
}>()

/** 每页条数档位从字典取，页面不再各写一份（`ui.md`「OA-01」）。 */
const pageSizes = keysetPageSizes

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
  <div class="data-table" :class="{ 'is-compact': tableSize === 'small' }">
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
    <!--
      UI-C08 的键集退化形态：只给每页条数与上一页／下一页，不显示总数与总页数
      （契约只返回 nextCursor 与 hasMore，不得伪造总数）。
    -->
    <div v-else-if="keyset()" class="data-table-pager">
      <span class="data-table-count">已加载 {{ data.length }} 条</span>
      <el-select
        v-if="limit !== undefined"
        class="data-table-size"
        :model-value="limit"
        @update:model-value="(value: number) => emit('update:limit', value)"
      >
        <el-option v-for="size in pageSizes" :key="size" :label="`每页 ${size} 条`" :value="size" />
      </el-select>
      <ActionButton variant="secondary" :disabled="!hasPrev" @click="emit('prev')">上一页</ActionButton>
      <ActionButton variant="secondary" :disabled="!hasMore" @click="emit('next')">下一页</ActionButton>
    </div>
  </div>
</template>

<style scoped>
/*
 * UI-C01：数据行 40、表头 44；紧凑档行高 32。
 * 归属范围：变量定义在本组件根类 `.data-table` 上，高度规则也只挂在该祖先之下，
 * 因此只作用于本组件渲染出的表格；页面里直接手写的 el-table（如批量结果表）既不在
 * `.data-table` 子树内，也不带本组件的 scoped 属性，命中不到这些规则。
 * 为什么用 height 而不是固定 line-height：表格单元格的 `height` 是下限语义，
 * 单元格内容换行或内嵌控件更高时行高随之撑开，不会把多行文本裁掉；只靠 line-height
 * 无法把行高拉到 40，因为 Element Plus 的 `.cell` 行高是 23，行高由内容决定。
 * 上下内边距归零：Element Plus 默认数据行 8px、表头 8px 的上下内边距会把 32px 高的
 * 按钮/标签行撑到 48px，归零后行高只由 height 一个来源决定；水平内边距在 `.cell` 上，
 * 不受影响（单元格自身的左右内边距本来就是 0）。
 */
.data-table {
  --data-table-row-height: 40px;
  --data-table-header-height: 44px;
}

/* 紧凑档沿用 `tableSize="small"` 入口，只降行高，表头仍按 UI-C01 的 44 */
.data-table.is-compact {
  --data-table-row-height: 32px;
}

.data-table :deep(.el-table__row > .el-table__cell) {
  height: var(--data-table-row-height);
  padding-top: 0;
  padding-bottom: 0;
}

.data-table :deep(.el-table__header th.el-table__cell) {
  height: var(--data-table-header-height);
  padding-top: 0;
  padding-bottom: 0;
}

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
/* 每页条数与翻页按钮同排，宽度按「每页 100 条」的最长文案留足，不与按钮挤在一起 */
.data-table-size {
  width: 120px;
  margin-right: 12px;
}
</style>
