/**
 * 时间展示格式化，只此一份（12 的 `UI-W13`、`D38`）。
 * 接口传 UTC，界面显示法域时间；转换只在本文件做一次，页面不各自拼时间串。
 * - 列表档：`YYYY-MM-DD HH:mm`
 * - 详情与审计档：`YYYY-MM-DD HH:mm:ss`
 */
function pad(value: number): string {
  return value < 10 ? `0${value}` : String(value)
}

function toDate(value?: string | null): Date | null {
  if (!value) {
    return null
  }
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? null : date
}

function format(value: string | null | undefined, withSeconds: boolean): string {
  const date = toDate(value)
  if (!date) {
    return '—'
  }
  const day = `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
  const clock = `${pad(date.getHours())}:${pad(date.getMinutes())}`
  return withSeconds ? `${day} ${clock}:${pad(date.getSeconds())}` : `${day} ${clock}`
}

/** 列表档：`YYYY-MM-DD HH:mm`。 */
export function formatListTime(value?: string | null): string {
  return format(value, false)
}

/** 详情与审计档：`YYYY-MM-DD HH:mm:ss`。 */
export function formatDetailTime(value?: string | null): string {
  return format(value, true)
}

/**
 * 界面时间转 UTC 时间戳，供列表的更新时间范围入参（契约「时间与金额口径」：时间用 UTC 时间戳）。
 * 空值与非法时间回空串，由查询串拼装按「未填」跳过。
 */
export function toUtcIso(value?: Date | null): string {
  if (!value) {
    return ''
  }
  return Number.isNaN(value.getTime()) ? '' : value.toISOString()
}

/** UTC 时间戳回填筛选控件；空值与非法值回 `null`。 */
export function parseUtcIso(value?: string | null): Date | null {
  return toDate(value)
}
