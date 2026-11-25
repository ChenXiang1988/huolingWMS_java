/**
 * 商品档案与单仓变更申请的接口声明，只此一份。
 *
 * 路径、方法、字段、错误码逐条对齐变更 `add-three-end-product-archive` 的「API 接口契约」章：
 * - API-001 `POST /api/owner/archives`
 * - API-002 `POST /api/owner/archives/{id}/submit`
 * - API-003 `PUT /api/owner/archives/{id}`
 * - API-004 `POST /api/owner/archives/{id}/disable`
 * - API-005 `GET /api/owner/archives`
 * - API-006 `POST /api/hub/archives`
 * - API-007 `GET /api/hub/archives`
 * - API-008 `POST /api/hub/archives/{id}/review`
 * - API-009 `POST /api/hub/archives/batch`
 * - API-010 `GET /api/site/archives`
 * - API-011 `POST /api/site/archive-change-requests`
 * - API-012 `GET /api/hub/archive-change-requests`
 * - API-013 `POST /api/hub/archive-change-requests/{id}/review`
 * - API-014 `GET /api/site/archive-change-requests`
 * - API-015 `GET /api/owner/archive-acceptances/{acceptNo}`
 * - API-016 `GET /api/hub/archive-acceptances/{acceptNo}`
 * - API-017 `GET /api/site/archive-acceptances/{acceptNo}`
 * - API-020 `GET /api/hub/owners`
 *
 * 字段英文名逐字取契约「字段结构与枚举」：档案标识为 `archiveId`、货主系统编码为 `ownerCode`、
 * 变更字段为 `changeField`、本仓标识为 `siteId`、变更申请编号为 `requestNo`、
 * 审核驳回原因为 `reviewReason`、包装换算率为 `packageConversionRate`。
 * 标识一律按十进制字符串传输，前端不做 Number／parseInt 转换。受理结果摘要按契约的 `result` 子字段收口：
 * `result.archiveId`、`result.requestId`、`result.items`，逐条结果条目含 `archiveId`、`success`、
 * `status`、`errorCode`、`message`。
 *
 * 审核与批量请求体按契约条目收口：单条审核只送 `action` 与 `reviewReason`（API-008、API-013），
 * 批量只送 `archiveIds`、`action` 与 `reason`（API-009，驳回或停用时必填）。
 * 契约没有在审核请求体里定义 `version`，因此不在请求体里带货架外的乐观锁字段。
 *
 * 写接口按契约「写接口统一响应」只返回 HTTP 202 的 `acceptNo` 与受理时状态快照，
 * 结果由按端分设的受理查询接口（API-015／API-016／API-017）取得，不在前端判断业务结果（D20）。
 */
import { newIdempotencyKey } from './keys'

/** 端前缀，取值见契约「基础路径与端分组」，端之间路径不混用。 */
export type ArchiveEnd = 'hub' | 'owner' | 'site'

/** 档案状态闭集，见契约「字段结构与枚举」。 */
export type ArchiveStatus = 'DRAFT' | 'PENDING' | 'ACTIVE' | 'REJECTED' | 'DISABLED'

/** 变更申请状态闭集，见契约「字段结构与枚举」。 */
export type ChangeRequestStatus = 'PENDING' | 'APPROVED' | 'REJECTED'

/**
 * 单仓可发起变更的字段闭集，见契约字段表 `changeField`：
 * `name`、`spec`、`category`、`storageCondition`。
 * `baseUnit` 不在闭集内，单仓不提供基本单位变更入口，需要改造走运营改档。
 */
export type ArchiveChangeField = 'name' | 'spec' | 'category' | 'storageCondition'

/** 审核动作，见 API-008／API-013「动作通过或驳回」，单条审核只接受这两个值。 */
export type ReviewAction = 'APPROVE' | 'REJECT'

/**
 * 批量动作，见 API-009：在审核两动作之外增加批量停用 `DISABLE`，
 * 驳回与停用时 `reason` 必填（契约 API-009「请求」）。
 */
export type BatchAction = ReviewAction | 'DISABLE'

/** 列表排序字段闭集，见契约字段表 `sortBy`。 */
export type ArchiveSortBy = 'updateTime' | 'ownerCode' | 'name'

/** 排序方向闭集，见契约字段表 `sortDir`，缺省 `desc`。 */
export type SortDir = 'asc' | 'desc'

/**
 * 写接口统一响应（契约「写接口统一响应」）：HTTP 202，只有受理号与受理时的业务状态快照，
 * 不含执行结果与最终版本号。`status` 不是「提交成功」，只是受理那一刻的业务状态。
 */
export interface AcceptTicket {
  acceptNo: string
  status: string
}

/** 受理状态闭集，见契约字段表 `acceptStatus`。 */
export type AcceptStatus = 'PROCESSING' | 'SUCCEEDED' | 'FAILED'

/**
 * 逐条结果条目，字段逐字取契约字段表 `result.items`：`archiveId`、`success`、
 * `status`、`errorCode`、`message`。单条失败不影响其他条目，故逐条带错误码与说明，
 * 界面不靠错误码反推成败。
 */
export interface BatchOutcomeItem {
  archiveId: string
  success: boolean
  status?: string | null
  errorCode?: string | null
  message?: string | null
}

/** 受理完成后的结果摘要（契约字段表 `result`），未完成为空。 */
export interface AcceptanceResult {
  /** `result.archiveId`：受理产生的档案编号。 */
  archiveId?: string | null
  /** `result.requestId`：API-011 受理结果给出的变更申请编号。 */
  requestId?: string | null
  /** `result.items`：批量操作的逐条结果，条目字段见 `BatchOutcomeItem`。 */
  items?: BatchOutcomeItem[]
}

/** API-015、API-016、API-017 的响应：受理状态、结果摘要、失败错误码与数据时间。 */
export interface AcceptanceOutcome {
  acceptStatus: AcceptStatus
  result?: AcceptanceResult | null
  errorCode?: string | null
  /** 受理存储降级时的数据时间（契约「降级约定」）。 */
  dataTimestamp?: string | null
}

/** 键集分页请求参数，见契约「分页约定」：`cursor` 与 `limit`，默认二十、上限一百。 */
export interface KeysetQuery {
  cursor?: string | null
  limit?: number
}

/**
 * 键集分页响应：返回条目、下一页游标与是否还有数据；
 * 查询降级时带 `dataTimestamp`（契约「降级约定」），界面按 `UI-S05` 展示数据时间。
 */
export interface KeysetResult<T> {
  items: T[]
  nextCursor: string | null
  hasMore: boolean
  dataTimestamp?: string | null
}

/** 列表排序参数，见契约字段表 `sortBy`、`sortDir`。 */
export interface ArchiveSortQuery {
  sortBy?: ArchiveSortBy | ''
  sortDir?: SortDir | ''
}

/**
 * 档案概要（契约字段表里出现位置含「响应」的档案字段）。列表只展示 `ownerCode`、`name`、
 * 归属、状态、版本与时间，其余进详情分区；包装换算率按契约英文名 `packageConversionRate`，
 * 审核驳回原因按契约英文名 `reviewReason`。契约字段表没有的展示字段不在此声明，
 * 避免页面把不存在的字段渲染成空值。
 */
export interface ArchiveItem {
  /** 系统编号，十进制字符串。 */
  archiveId: string
  /** 归属货主，十进制字符串；创建后不可变。 */
  ownerId: string
  /** 货主系统编码，货主内唯一。 */
  ownerCode: string
  name: string
  barcode: string
  baseUnit: string
  storageCondition: string
  status: ArchiveStatus
  version: number
  category?: string | null
  spec?: string | null
  brand?: string | null
  barcodeType?: string | null
  packageSpec?: string | null
  /** 定点包装换算率，字符串承载（契约「时间与金额口径」，禁止浮点）。 */
  packageConversionRate?: string | null
  netWeight?: string | null
  grossWeight?: string | null
  volume?: string | null
  length?: string | null
  width?: string | null
  height?: string | null
  highValue?: boolean | null
  hazardous?: boolean | null
  fragile?: boolean | null
  batchManaged?: boolean | null
  serialManaged?: boolean | null
  shelfLifeDays?: number | null
  remark?: string | null
  createdAt?: string | null
  updatedAt?: string | null
  /** 审核驳回原因，契约英文名 `reviewReason`。 */
  reviewReason?: string | null
}

/** 档案可写字段，创建与编辑共用；归属货主不在其中（创建后不可变）。 */
export interface ArchiveWriteFields {
  name: string
  ownerCode: string
  barcode: string
  baseUnit: string
  storageCondition: string
  category?: string
  spec?: string
  brand?: string
  barcodeType?: string
  packageSpec?: string
  /** 定点包装换算率，字符串承载；有包装规格时必填（服务端判定）。 */
  packageConversionRate?: string
  netWeight?: string
  grossWeight?: string
  volume?: string
  length?: string
  width?: string
  height?: string
  highValue?: boolean
  hazardous?: boolean
  fragile?: boolean
  batchManaged?: boolean
  serialManaged?: boolean
  shelfLifeDays?: number | null
  remark?: string
}

/** API-001 请求体：货主端建档，归属由登录上下文带入。 */
export type OwnerCreateBody = ArchiveWriteFields

/** API-003 请求体：允许修改字段加版本号（乐观锁）。 */
export type OwnerUpdateBody = Partial<ArchiveWriteFields> & { version: number }

/** API-002 请求体：可空；并发冲突以版本号比对。 */
export interface OwnerSubmitBody {
  version: number
}

/** API-004 请求体：停用原因与版本号。 */
export interface OwnerDisableBody {
  reason: string
  version: number
}

/** API-006 请求体：运营代建，必须指定归属货主（归属由 API-020 选择器给出）。 */
export type HubCreateBody = ArchiveWriteFields & { ownerId: string }

/**
 * API-008 请求体：只有动作与驳回原因两项（契约 API-008「请求」）。
 * 驳回原因取契约字段名 `reviewReason`，驳回时必填；通过时不带该字段。
 */
export interface ReviewBody {
  action: ReviewAction
  reviewReason?: string
}

/**
 * API-009 请求体：条目编号数组 `archiveIds`、动作 `action` 与原因 `reason`，单次上限一百。
 * 驳回或停用时 `reason` 必填（契约 API-009「请求」）。
 */
export interface BatchBody {
  archiveIds: string[]
  action: BatchAction
  reason?: string
}

/** API-005 查询参数：编码、名称、状态、分类、更新时间范围、排序与键集游标。 */
export interface OwnerArchiveQuery extends KeysetQuery, ArchiveSortQuery {
  ownerCode?: string
  name?: string
  status?: ArchiveStatus | ''
  category?: string
  updatedFrom?: string
  updatedTo?: string
}

/** API-007 查询参数：归属货主、编码、状态、分类、更新时间范围、排序与键集游标。 */
export interface HubArchiveQuery extends KeysetQuery, ArchiveSortQuery {
  ownerId?: string
  ownerCode?: string
  status?: ArchiveStatus | ''
  category?: string
  updatedFrom?: string
  updatedTo?: string
}

/** API-010 查询参数：编码、名称、分类、更新时间范围、排序与键集游标。 */
export interface SiteArchiveQuery extends KeysetQuery, ArchiveSortQuery {
  ownerCode?: string
  name?: string
  category?: string
  updatedFrom?: string
  updatedTo?: string
}

/**
 * 单仓变更申请条目（契约 API-012／API-014「响应」）。状态词见 `@wms/dict` 的
 * `changeRequestStatusLabels`。
 *
 * 字段英文名逐字取契约字段表：申请编号为 `requestNo`（不是 `id`），审核驳回原因为 `reviewReason`
 * （不是 `rejectReason`）。契约响应里没有档案名称、生效时间与审核人，故不声明这些字段，
 * 页面也不按这些名字取值，避免详情分区静默为空。
 */
export interface ChangeRequestItem {
  /** 申请编号，十进制字符串；API-013 的路径参数用它。 */
  requestNo: string
  /** 档案编号，十进制字符串。 */
  archiveId: string
  ownerId?: string | null
  /** 本仓标识，十进制字符串；数据域取令牌里的唯一 `siteId`。 */
  siteId?: string | null
  /** 变更字段，闭集见 `ArchiveChangeField`。 */
  changeField: string
  targetValue: string
  /** 发起申请时填写的原因。 */
  reason: string
  status: ChangeRequestStatus
  /** 审核驳回原因，契约字段名 `reviewReason`。 */
  reviewReason?: string | null
  createdAt?: string | null
  updatedAt?: string | null
}

/** API-011 请求体：档案编号、变更字段、目标值与原因。 */
export interface ChangeRequestCreateBody {
  archiveId: string
  changeField: ArchiveChangeField
  targetValue: string
  reason: string
}

/**
 * API-013 请求体：只有动作与驳回原因两项（契约 API-013「请求」）。
 * 驳回原因取契约字段名 `reviewReason`，驳回时必填；契约未在请求体里定义 `version`，故不带。
 */
export interface ChangeRequestReviewBody {
  action: ReviewAction
  reviewReason?: string
}

/** API-012 查询参数：归属货主、本仓、状态、排序与键集游标。 */
export interface HubChangeRequestQuery extends KeysetQuery, ArchiveSortQuery {
  ownerId?: string
  siteId?: string
  status?: ChangeRequestStatus | ''
}

/** API-014 查询参数：申请编号 `requestNo`、档案编号、状态、排序与键集游标。 */
export interface SiteChangeRequestQuery extends KeysetQuery, ArchiveSortQuery {
  /** 申请编号筛选，契约字段名 `requestNo`。 */
  requestNo?: string
  /** 按档案过滤，用于同一档案待审申请的前置判定。 */
  archiveId?: string
  status?: ChangeRequestStatus | ''
}

/** API-020 货主选择器条目：`ownerId` 与展示名 `ownerName`（展示字段，不作查询参数）。 */
export interface OwnerOption {
  ownerId: string
  ownerName: string
}

/**
 * API-020 查询参数：编码 `ownerCode`、名称 `name` 与键集游标（契约「API-020 运营查询货主选择器」：
 * 「查询参数含编码、名称与键集分页游标」）。
 * `ownerName` 只是响应展示字段（字段表出现位置为「API-020 响应」），不下发为查询参数。
 */
export interface OwnerOptionQuery extends KeysetQuery {
  ownerCode?: string
  name?: string
}

/**
 * 请求发送方的最小结构。`createHttp` 的返回值天然满足它，
 * 这里不直接引用请求层实现，避免入口模块之间互相依赖。
 */
export interface RequestSender {
  get: <T>(path: string) => Promise<T>
  post: <T>(path: string, body?: unknown, idempotencyKey?: string) => Promise<T>
  put: <T>(path: string, body?: unknown, idempotencyKey?: string) => Promise<T>
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

/** 端前缀，取值见契约「基础路径与端分组」。 */
export const archiveBasePath: Record<ArchiveEnd, string> = {
  owner: '/api/owner',
  hub: '/api/hub',
  site: '/api/site',
}

/** 受理查询路径，见 API-015、API-016、API-017：按端分设，查询参数为 `acceptNo`。 */
export const acceptanceBasePath: Record<ArchiveEnd, string> = {
  owner: `${archiveBasePath.owner}/archive-acceptances`,
  hub: `${archiveBasePath.hub}/archive-acceptances`,
  site: `${archiveBasePath.site}/archive-acceptances`,
}

/**
 * 货主端接口（API-001~API-005、API-015）。
 * 写入一律带幂等键；同一表单重提时把首次的键原样传回，避免生成第二个档案（契约「幂等约定」）。
 */
export function createOwnerArchiveApi(http: RequestSender) {
  const base = `${archiveBasePath.owner}/archives`
  return {
    /** API-001 创建草稿。 */
    create: (body: OwnerCreateBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(base, body, idempotencyKey),
    /** API-002 提交审核。 */
    submit: (id: string, body: OwnerSubmitBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(`${base}/${id}/submit`, body, idempotencyKey),
    /** API-003 编辑草稿。 */
    update: (id: string, body: OwnerUpdateBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.put<AcceptTicket>(`${base}/${id}`, body, idempotencyKey),
    /** API-004 停用（逻辑删除，档案与历史流水保留）。 */
    disable: (id: string, body: OwnerDisableBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(`${base}/${id}/disable`, body, idempotencyKey),
    /** API-005 查询自有档案。 */
    list: (query: OwnerArchiveQuery) =>
      http.get<KeysetResult<ArchiveItem>>(`${base}${buildQuery({ ...query })}`),
    /** API-015 查询受理结果（每 3 秒轮询、最长 60 秒由 `AcceptPanel` 承担）。 */
    getAcceptance: (acceptNo: string) =>
      http.get<AcceptanceOutcome>(`${acceptanceBasePath.owner}/${acceptNo}`),
  }
}

/** 运营端接口（API-006~API-009、API-012、API-013、API-016）。 */
export function createHubArchiveApi(http: RequestSender) {
  const archives = `${archiveBasePath.hub}/archives`
  const changeRequests = `${archiveBasePath.hub}/archive-change-requests`
  return {
    /** API-006 代建草稿，必须指定归属货主。 */
    create: (body: HubCreateBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(archives, body, idempotencyKey),
    /** API-007 全量查询。 */
    list: (query: HubArchiveQuery) =>
      http.get<KeysetResult<ArchiveItem>>(`${archives}${buildQuery({ ...query })}`),
    /** API-008 单条审核。 */
    review: (id: string, body: ReviewBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(`${archives}/${id}/review`, body, idempotencyKey),
    /** API-009 批量通过、驳回或停用：逐条结果由 API-016 的受理查询返回。 */
    batch: (body: BatchBody, idempotencyKey: string = newIdempotencyKey()) =>
      http.post<AcceptTicket>(`${archives}/batch`, body, idempotencyKey),
    /** API-012 查询变更申请。 */
    listChangeRequests: (query: HubChangeRequestQuery) =>
      http.get<KeysetResult<ChangeRequestItem>>(`${changeRequests}${buildQuery({ ...query })}`),
    /** API-013 审核变更申请，路径参数取契约字段 `requestNo`。 */
    reviewChangeRequest: (
      requestNo: string,
      body: ChangeRequestReviewBody,
      idempotencyKey: string = newIdempotencyKey(),
    ) => http.post<AcceptTicket>(`${changeRequests}/${requestNo}/review`, body, idempotencyKey),
    /** API-016 查询受理结果（批量逐条结果在 `result.items`）。 */
    getAcceptance: (acceptNo: string) =>
      http.get<AcceptanceOutcome>(`${acceptanceBasePath.hub}/${acceptNo}`),
  }
}

/** 单仓端接口（API-010、API-011、API-014、API-017）。 */
export function createSiteArchiveApi(http: RequestSender) {
  const archives = `${archiveBasePath.site}/archives`
  const changeRequests = `${archiveBasePath.site}/archive-change-requests`
  return {
    /** API-010 查询本仓生效档案，降级时带数据时间戳。 */
    list: (query: SiteArchiveQuery) =>
      http.get<KeysetResult<ArchiveItem>>(`${archives}${buildQuery({ ...query })}`),
    /** API-011 发起变更申请。 */
    createChangeRequest: (
      body: ChangeRequestCreateBody,
      idempotencyKey: string = newIdempotencyKey(),
    ) => http.post<AcceptTicket>(changeRequests, body, idempotencyKey),
    /** API-014 查询变更申请状态；按 `archiveId` 过滤可用于待审前置判定。 */
    listChangeRequests: (query: SiteChangeRequestQuery) =>
      http.get<KeysetResult<ChangeRequestItem>>(`${changeRequests}${buildQuery({ ...query })}`),
    /** API-017 查询受理结果。 */
    getAcceptance: (acceptNo: string) =>
      http.get<AcceptanceOutcome>(`${acceptanceBasePath.site}/${acceptNo}`),
  }
}

/**
 * 运营端货主选择器（API-020）。
 * 代建归属必须从服务端给出的货主里选，不在前端手填标识（ui.md「运营端 代建归属选择」）。
 */
export function createHubOwnerApi(http: RequestSender) {
  const base = `${archiveBasePath.hub}/owners`
  return {
    /** API-020 按编码或名称搜索货主。 */
    search: (query: OwnerOptionQuery) =>
      http.get<KeysetResult<OwnerOption>>(`${base}${buildQuery({ ...query })}`),
  }
}
