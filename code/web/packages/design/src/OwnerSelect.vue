<script setup lang="ts">
import { computed, onBeforeUnmount, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import ActionButton from './ActionButton.vue'

/**
 * 货主选择器，对应 12 的 `UI-B01`、`UI-B02`：
 * - 五类选择器共用同一结构，本组件是「货主」这一类，只换数据源；
 * - 触发器为输入框样式、右侧搜索图标，已选显示「货主名称（编号）」，超宽省略（`UI-B01`）；
 * - 远程搜索，输入防抖 300ms；数据范围由服务端按当前账号的数据域决定（`UI-B02`），组件不做过滤；
 * - 只回显名称与编号，不把内部标识显示给用户（`UI-B01`「回显」行）。
 *
 * 数据源由调用方以 `loader` 注入：`@wms/design` 不依赖 `@wms/request`，
 * 接口声明只在 `packages/request` 一处（API-020，查询参数为 `ownerNo`／`ownerName`）。
 * 候选只含可用货主由服务端保证（契约 API-020「响应」），组件不按状态过滤、不缓存候选。
 *
 * 空态与失败态按 `UI-S02`、`UI-S04`：无可用货主给主文案与「打开货主档案」出口，
 * 检索失败给错误码与「重试」，都不留空白下拉。
 */

/** 候选条目的最小结构，字段与契约 API-020 响应一致；组件不关心其余字段。 */
export interface OwnerSelectOption {
  ownerId: string
  ownerNo: string
  ownerName: string
}

/** 一次检索的返回：条目与是否还有下一页（契约 API-020 的 `nextCursor`、`hasMore`）。 */
export interface OwnerSelectPage {
  items: OwnerSelectOption[]
  nextCursor?: string | null
  hasMore?: boolean
}

/** 检索参数，字段与契约 API-020 请求一致（`ownerNo` 精确、`ownerName` 模糊）。 */
export interface OwnerSelectQuery {
  ownerNo?: string
  ownerName?: string
  cursor?: string | null
  limit?: number
}

const props = withDefaults(
  defineProps<{
    /** 已选货主的内部标识（`ownerId`）；空串表示未选。 */
    modelValue: string
    /** 远程检索，由调用方绑定到 API-020。 */
    loader: (query: OwnerSelectQuery) => Promise<OwnerSelectPage>
    /** 已选项，用于编辑场景按标识回显名称与编号（`UI-B01`「回显」行）。 */
    selected?: OwnerSelectOption | null
    placeholder?: string
    disabled?: boolean
    /** 下拉为空时的出口：跳货主档案列表页（路由由调用方给，组件不写死路径）。 */
    onOpenArchive?: () => void
  }>(),
  {
    selected: null,
    placeholder: '输入货主编号或名称搜索',
    disabled: false,
    onOpenArchive: undefined,
  },
)

const emit = defineEmits<{
  (e: 'update:modelValue', value: string): void
  /** 选中条目，供调用方同时拿到名称与编号（展示「名称（编号）」用）。 */
  (e: 'select', option: OwnerSelectOption | null): void
}>()

/** `UI-B01`：输入防抖 300ms。 */
const DEBOUNCE_MS = 300
/** 与契约 API-020 的缺省 `limit` 同值，避免一次拉太多候选。 */
const PAGE_SIZE = 20

const options = ref<OwnerSelectOption[]>([])
/** 已选条目的本地回显：选中时记下，编辑场景由 `selected` 传参带入。 */
const picked = ref<OwnerSelectOption | null>(props.selected)
const loading = ref(false)
const error = ref('')
const errorCode = ref('')
const keyword = ref('')
let debounceTimer: number | undefined
/** 请求代次：防旧请求覆盖新结果。 */
let generation = 0

/**
 * 候选列表 = 本次检索结果，已选项始终在内。
 * 编辑场景按标识回显名称时，该项可能不在第一页结果里，注入后触发器才不会直接显示内部标识（`UI-B01`）。
 */
const optionList = computed<OwnerSelectOption[]>(() => {
  const list = options.value
  const current = picked.value
  if (!current || list.some((item) => item.ownerId === current.ownerId)) {
    return list
  }
  return [current, ...list]
})

const emptyTitle = computed(() => (error.value ? '货主检索失败' : '暂无可用货主'))
const emptyHint = computed(() =>
  error.value ? error.value : '货主档案中没有状态为可用的货主',
)

/**
 * 检索：以 `OW` 开头按货主编号精确检索，其余按货主名称模糊检索。
 * 契约同时给出两个条件时取交集，故一次只填一个，避免把编号当名称查成空。
 */
async function search(raw: string): Promise<void> {
  const value = raw.trim()
  const byNo = /^OW/i.test(value)
  generation += 1
  const current = generation
  loading.value = true
  error.value = ''
  errorCode.value = ''
  try {
    const page = await props.loader({
      ownerNo: byNo ? value : '',
      ownerName: byNo ? '' : value,
      limit: PAGE_SIZE,
    })
    if (current !== generation) {
      return
    }
    options.value = page.items
  } catch (e) {
    if (current !== generation) {
      return
    }
    const failure = e as { code?: number | string; message?: string } | null
    // 失败文案与错误码原样呈现，页面不再包一层（`UI-S04`、`UI-S06`）
    error.value = failure?.message || '请求失败，请稍后重试'
    errorCode.value = failure?.code === undefined || failure?.code === null ? '' : String(failure.code)
    options.value = []
  } finally {
    if (current === generation) {
      loading.value = false
    }
  }
}

/** 输入防抖 300ms 后检索（`UI-B01`）。 */
function onFilter(value: string): void {
  keyword.value = value
  if (debounceTimer) {
    window.clearTimeout(debounceTimer)
  }
  debounceTimer = window.setTimeout(() => {
    debounceTimer = undefined
    void search(value)
  }, DEBOUNCE_MS)
}

/** 展开时没有候选就先取一页，供直接浏览（`UI-S02` 的空态不是空白下拉）。 */
function onVisibleChange(visible: boolean): void {
  if (visible && options.value.length === 0 && !loading.value) {
    void search(keyword.value)
  }
}

function onSelect(ownerId: string): void {
  const option = optionList.value.find((item) => item.ownerId === ownerId) ?? null
  picked.value = option
  emit('update:modelValue', ownerId)
  emit('select', option)
}

function onClear(): void {
  picked.value = null
  emit('update:modelValue', '')
  emit('select', null)
}

function retry(): void {
  void search(keyword.value)
}

onBeforeUnmount(() => {
  if (debounceTimer) {
    window.clearTimeout(debounceTimer)
  }
})
</script>

<template>
  <el-select
    class="owner-select"
    :model-value="modelValue"
    :disabled="disabled"
    filterable
    remote
    clearable
    :remote-method="onFilter"
    :loading="loading"
    :placeholder="placeholder"
    @update:model-value="(value: string) => onSelect(value)"
    @clear="onClear"
    @visible-change="onVisibleChange"
  >
    <!-- UI-B01：触发器右侧搜索图标，输入框样式 -->
    <template #prefix>
      <el-icon class="owner-select__icon"><Search /></el-icon>
    </template>
    <el-option
      v-for="item in optionList"
      :key="item.ownerId"
      :label="`${item.ownerName}（${item.ownerNo}）`"
      :value="item.ownerId"
    />
    <!-- UI-S02、UI-S04：空态三段与失败态的重试都在下拉内，不收敛成空白 -->
    <template #empty>
      <div class="owner-select__empty">
        <p class="owner-select__empty-title">{{ emptyTitle }}</p>
        <p class="owner-select__empty-hint">
          {{ emptyHint }}<template v-if="errorCode">（{{ errorCode }}）</template>
        </p>
        <div class="owner-select__empty-actions">
          <ActionButton v-if="error" variant="text" @click="retry">重试</ActionButton>
          <ActionButton v-else-if="onOpenArchive" variant="text" @click="onOpenArchive">
            打开货主档案
          </ActionButton>
        </div>
      </div>
    </template>
  </el-select>
</template>

<style scoped>
/* UI-B01：触发器占满字段宽度，超宽省略交给 Element Plus 的选中项与 option 文案 */
.owner-select {
  width: 100%;
}
.owner-select__icon {
  color: var(--el-text-color-secondary);
}
/*
 * UI-S02：空态三段（没有什么 / 为什么 / 能做什么），内边距取 4 的倍数（UI-T14），
 * 文案用辅助色与 13 档（UI-T12），不与正常候选抢视觉。
 */
.owner-select__empty {
  padding: 12px;
  text-align: left;
}
.owner-select__empty-title {
  margin: 0;
  font-size: 14px;
  line-height: 1.5;
  color: var(--el-text-color-primary);
}
.owner-select__empty-hint {
  margin: 4px 0 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}
.owner-select__empty-actions {
  display: flex;
  align-items: center;
  margin-top: 8px;
}
</style>
