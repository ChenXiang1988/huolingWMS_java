import { computed, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'

/**
 * 列表页查询条件与键集游标写进地址栏（`UI-X04`）。
 *
 * 契约「分页约定」只允许键集分页（`cursor` + `limit`），服务端不返回总数，
 * 因此不记页码而记游标；上一页靠内存里的游标栈回退。
 * 本文件只做「地址栏 ↔ 页面状态」同步，不判定任何业务规则。
 * 三端产物独立部署，故各端各自持有一份，不做跨端引用（`01`）。
 */
export function useListQuery<K extends string>(keys: readonly K[]) {
  const route = useRoute()
  const router = useRouter()

  const initial: Record<string, string> = {}
  keys.forEach((key) => {
    const value = route.query[key]
    initial[key] = typeof value === 'string' ? value : ''
  })
  const filter = reactive(initial) as Record<K, string>

  const cursorValue = route.query.cursor
  const cursor = ref<string | null>(typeof cursorValue === 'string' && cursorValue ? cursorValue : null)
  const stack = ref<string[]>([])

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
    void router.replace({ query })
  }

  function search() {
    cursor.value = null
    stack.value = []
    sync()
  }

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

  const canPrev = computed(() => stack.value.length > 0)

  return { filter, cursor, canPrev, sync, search, reset, next, prev }
}
