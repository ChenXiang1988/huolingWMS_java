import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/** 每页条数的缺省档，与契约「分页」的 `limit` 缺省值同值（20），也是 `ui.md`「OA-01」的最低档。 */
const DEFAULT_LIMIT = 20

/** 每页条数只接受 20／50／100，其余（含地址栏被改坏）落回缺省值（`ui.md`「OA-01」）。 */
const PAGE_LIMITS = [20, 50, 100]

function toLimit(value: unknown): number {
  const parsed = typeof value === 'string' ? Number(value) : NaN
  return PAGE_LIMITS.includes(parsed) ? parsed : DEFAULT_LIMIT
}

/**
 * 列表页查询条件、键集游标与每页条数写进地址栏（`UI-X04`、`UI-C02`「折叠状态在 URL 中保留」）。
 *
 * 契约「分页约定」只允许键集分页（`cursor` + `limit`），服务端不返回总数，
 * 因此不记页码而记游标；上一页靠内存里的游标栈回退，刷新后以当前游标为起点。
 * 改每页条数、改排序与改筛选都重置游标并清空上一页栈（`UI-I09`、`ui.md`「OA-01」的分页口径）。
 * 本文件只做「地址栏 ↔ 页面状态」同步，不判定任何业务规则。
 */
export function useListQuery<K extends string>(keys: readonly K[]) {
  const route = useRoute()
  const router = useRouter()

  const initial: Record<string, string> = {}
  keys.forEach((key) => {
    const value = route.query[key]
    initial[key] = typeof value === 'string' ? value : ''
  })
  /** 对外按受限键集合使用，键来自调用方传入的 `keys`。 */
  const filter = reactive(initial) as Record<K, string>

  const cursorValue = route.query.cursor
  const cursor = ref<string | null>(typeof cursorValue === 'string' && cursorValue ? cursorValue : null)
  /** 已走过的游标，用于「上一页」；只在本次会话内有效。 */
  const stack = ref<string[]>([])
  /** 每页条数，随游标一起进出地址栏；缺省 20 时不写进 URL，避免给不传该参数的页面污染地址。 */
  const limit = ref<number>(toLimit(route.query.limit))

  function sync() {
    const query: Record<string, string> = {}
    keys.forEach((key) => {
      const value = filter[key]
      if (value) {
        query[key] = value
      }
    })
    if (cursor.value) {
      query.cursor = cursor.value
    }
    if (limit.value !== DEFAULT_LIMIT) {
      query.limit = String(limit.value)
    }
    void router.replace({ query })
  }

  /** 查询：条件变化后游标回到起点（`UI-I09`「筛选变化后回到第 1 页」）。 */
  function search() {
    cursor.value = null
    stack.value = []
    sync()
  }

  /** 重置：清空全部条件并回到起点（`UI-W01`「重置」）。 */
  function reset() {
    keys.forEach((key) => {
      filter[key] = ''
    })
    search()
  }

  function next(nextCursor: string | null) {
    if (!nextCursor) {
      return
    }
    stack.value = [...stack.value, cursor.value ?? '']
    cursor.value = nextCursor
    sync()
  }

  function prev() {
    const back = stack.value[stack.value.length - 1]
    stack.value = stack.value.slice(0, -1)
    cursor.value = back ? back : null
    sync()
  }

  /** 改每页条数：游标回起点并清空上一页栈，不残留旧游标（`ui.md`「OA-01」、2.11）。 */
  function setLimit(value: number) {
    limit.value = toLimit(String(value))
    cursor.value = null
    stack.value = []
    sync()
  }

  const canPrev = computed(() => stack.value.length > 0)

  return { filter, cursor, limit, canPrev, sync, search, reset, next, prev, setLimit }
}
