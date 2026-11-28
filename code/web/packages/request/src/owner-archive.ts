/**
 * 货主档案接口声明，只此一份。
 *
 * 路径、方法、字段、错误码逐条对齐变更 `add-owner-archive` 的「API 接口契约」章：
 * - API-021 `GET /api/hub/owner-archives`（列表，四项筛选 + 键集分页 + 单字段排序）
 * - API-022 `GET /api/hub/owner-archives/{ownerNo}`（详情）
 * - API-023 `POST /api/hub/owner-archives`（建档）
 * - API-024 `PUT /api/hub/owner-archives/{ownerNo}`（改档，全量替换 + `version`）
 * - API-025 `POST /api/hub/owner-archives/{ownerNo}/disable`（停用）
 * - API-026 `POST /api/hub/owner-archives/{ownerNo}/enable`（重新启用）
 * - API-027 `GET /api/hub/owner-archives/acceptances/{acceptNo}`（受理结果查询）
 *
 * 字段英文名逐字取契约「字段结构与枚举」：对外标识一律 `ownerNo`（不可变、可分享），
 * `ownerId` 只作为既有契约键透传，不用于寻址；时间字段为 `createdTime`、`updateTime`；
 * 最近一次状态变更为 `lastStatusChange`（`action`／`reason`／`operator`／`changedAt`）。
 *
 * 写入一律走 202 受理（契约 1.6）：响应只有受理票据，结果由 API-027 取得，前端不判定业务结果（D20）。
 * 四个写接口都读 `Idempotency-Key` 请求头（幂等约定见契约 1.3），幂等键由调用方按
 * 「人工重提换新键、传输层重试沿用原键」的规则给（`OA-10`）。
 */
import { newIdempotencyKey } from './keys'
import { archiveBasePath, type KeysetQuery, type KeysetResult, type SortDir } from './archive'

/** 货主档案状态闭集，见契约字段表 `status`。只表达主数据可用性，不表达合同是否生效。 */
export type OwnerStatus = 'ACTIVE' | 'DISABLED'

/** 最近一次状态变更的动作闭集，见契约字段表 `lastStatusChange.action`。 */
export type OwnerStatusChangeAction = 'CREATE' | 'DISABLE' | 'ENABLE'

/** 列表排序字段闭集，见契约 API-021「请求」的排序白名单。 */
export type OwnerSortBy = 'ownerNo' | 'ownerName' | 'updateTime'

/** 列表条目，字段逐字取契约 API-021「响应」。 */
export interface OwnerArchiveItem {
  /** 货主标识，十进制字符串；只作为既有契约键透传，不用于寻址。 */
  ownerId: string
  /** 货主编号，8 至 32 位、以 `OW` 开头；系统生成、全局唯一、不可变。 */
  ownerNo: string
  ownerName: string
  contactName: string
  contactPhone: string
  creditCode: string
  status: OwnerStatus
  version: number
  createdTime: string
  updateTime: string
}

/** 最近一次状态变更，见契约字段表 `lastStatusChange`；`CREATE` 时 `reason` 为空。 */
export interface OwnerStatusChange {
  action: OwnerStatusChangeAction
  reason?: string | null
  /** 操作人账号名。 */
  operator: string
  changedAt: string
}

/**
 * 详情条目，见契约 API-022「响应」：列表全部字段外加创建与修改留痕（`D24`）与
 * `lastStatusChange`（建档后必有值）。
 */
export interface OwnerArchiveDetail extends OwnerArchiveItem {
  createdBy: string
  updatedBy?: string | null
  lastStatusChange?: OwnerStatusChange | null
}

/**
 * API-021 查询参数：四项筛选加信用代码、排序与键集游标，逐字取契约 API-021「请求」。
 * 命名带 `Hub` 前缀，与商品档案变更里货主端列表的 `OwnerArchiveQuery`（API-005）区分开。
 */
export interface HubOwnerArchiveQuery extends KeysetQuery {
  /** 货主编号，精确匹配。 */
  ownerNo?: string
  /** 货主名称，模糊匹配。 */
  ownerName?: string
  /** 统一社会信用代码，精确匹配，18 位。 */
  creditCode?: string
  status?: OwnerStatus | ''
  /** UTC 时间，按最后修改时间过滤，闭区间。 */
  updatedFrom?: string
  updatedTo?: string
  sortBy?: OwnerSortBy | ''
  sortDir?: SortDir | ''
}

/**
 * API-023 建档请求体：四项全部必填。请求体不含 `ownerNo`、`status`、`version`
 * （系统生成或置初值，传入即未知字段被拒，契约 API-023「请求」）。
 */
export interface OwnerArchiveCreateBody {
  ownerName: string
  contactName: string
  contactPhone: string
  creditCode: string
}

/**
 * API-024 改档请求体：四项必填（全量替换）加 `version`（乐观锁）。
 * `ownerNo` 在路径参数里、`status` 走独立动作，两者都不在请求体内。
 */
export type OwnerArchiveUpdateBody = OwnerArchiveCreateBody & { version: number }

/** API-025、API-026 请求体：原因与版本号都必填（`reason` 1 至 200 字）。 */
export interface OwnerStatusChangeBody {
  reason: string
  version: number
}

/**
 * 货主档案的 202 受理票据（契约 1.6）：`status` 是受理时的业务状态快照，不是执行结果；
 * `replayed` 为真表示同一幂等键的重复请求、沿用首次受理号。
 */
export interface OwnerAcceptTicket {
  acceptNo: string
  status: OwnerStatus
  replayed: boolean
}

/** 受理状态闭集，见契约字段表 `acceptStatus`。 */
export type OwnerAcceptStatus = 'PROCESSING' | 'SUCCEEDED' | 'FAILED'

/** API-027 的结果摘要，见契约字段表 `result`；未完成为空。 */
export interface OwnerAcceptanceResult {
  ownerId: string
  ownerNo: string
  status: OwnerStatus
  version: number
}

/**
 * API-027 响应：受理状态、结果摘要、失败时的错误码与说明。
 * 失败错误码取自契约 1.4 表的符号码（如 `CONFLICT_VERSION`），不是 HTTP 错误。
 * 契约未在响应里给出 `traceId`，故不声明该字段（见返回的契约缺口登记）。
 */
export interface OwnerAcceptanceOutcome {
  acceptStatus: OwnerAcceptStatus
  result?: OwnerAcceptanceResult | null
  errorCode?: string | null
  errorMessage?: string | null
}

/** 查询串只拼有值的参数，空串与 null 不下发。 */
function buildQuery(params: Record<string, string | number | null | undefined>): string {
  const search = new URLSearchParams()
  Object.entries(params).forEach(([key, value]) => {
    if (value === undefined || value === null || value === '') {
      return
    }
    search.set(key, String(value))
  })
  const query = search.toString()
  return query ? `?${query}` : ''
}

/** 请求发送方的最小结构，与 `archive.ts` 同形，避免接口模块之间互相依赖。 */
export interface OwnerRequestSender {
  get: <T>(path: string) => Promise<T>
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) => Promise<T>
  put: <T>(path: string, body?: unknown, idempotencyKey?: string) => Promise<T>
}

/** 货主档案接口路径前缀，取契约 1.1「基础路径与端分组」的运营端前缀。 */
export const ownerArchiveBasePath = `${archiveBasePath.hub}/owner-archives`

/**
 * 运营端货主档案接口（API-021 至 API-027）。
 * 写接口的幂等键由调用方传入：同一表单重提沿用首次键，人工修改后重提换新键（`OA-10`）。
 */
export function createHubOwnerArchiveApi(http: OwnerRequestSender) {
  const base = ownerArchiveBasePath
  return {
    /** API-021 列表：四项筛选可任意组合，组合取交集。 */
    list: (query: HubOwnerArchiveQuery) => http.get<KeysetResult<OwnerArchiveItem>>(`${base}${buildQuery({ ...query })}`),
    /** API-022 详情：`ownerNo` 为路径参数；域外与不存在同一响应 `NOT_FOUND_OWNER`。 */
    detail: (ownerNo: string) => http.get<OwnerArchiveDetail>(`${base}/${ownerNo}`),
    /** API-023 建档：只做字段级校验，信用代码判重与状态判定在受理后（契约「业务校验的时点」）。 */
    create: (body: OwnerArchiveCreateBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<OwnerAcceptTicket>(base, body, idempotencyKey),
    /** API-024 改档：全量替换四项并带进入表单时取到的 `version`。 */
    update: (ownerNo: string, body: OwnerArchiveUpdateBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.put<OwnerAcceptTicket>(`${base}/${ownerNo}`, body, idempotencyKey),
    /** API-025 停用：`reason` 与 `version` 都必填。 */
    disable: (ownerNo: string, body: OwnerStatusChangeBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<OwnerAcceptTicket>(`${base}/${ownerNo}/disable`, body, idempotencyKey),
    /** API-026 重新启用：只能由人发起，无系统自动路径。 */
    enable: (ownerNo: string, body: OwnerStatusChangeBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<OwnerAcceptTicket>(`${base}/${ownerNo}/enable`, body, idempotencyKey),
    /** API-027 受理结果：轮询节奏（首发 1 秒、此后 3 秒、最长 60 秒）由 `AcceptPanel` 承担。 */
    getAcceptance: (acceptNo: string) => http.get<OwnerAcceptanceOutcome>(`${base}/acceptances/${acceptNo}`),
  }
}
