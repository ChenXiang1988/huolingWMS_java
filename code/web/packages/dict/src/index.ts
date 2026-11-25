/**
 * 状态词表。取值只与后端枚举一致，界面文案集中在这里，不散落到页面。
 */

export const accountStatusLabels: Record<string, string> = {
  ENABLED: '启用',
  DISABLED: '停用',
}

export const accountStatusTags: Record<string, 'success' | 'info'> = {
  ENABLED: 'success',
  DISABLED: 'info',
}

/**
 * 受理状态词。商品档案的受理查询（API-015~API-017）取值见契约字段表 `acceptStatus`：
 * `PROCESSING`、`SUCCEEDED`、`FAILED`；`PENDING`／`RUNNING` 保留给身份模块的受理结果，
 * 两套码共用同一张词表，页面不各自判断状态取值。
 */
export const acceptanceStatusLabels: Record<string, string> = {
  PENDING: '待处理',
  PROCESSING: '处理中',
  RUNNING: '处理中',
  SUCCEEDED: '成功',
  FAILED: '失败',
}

export const acceptanceStatusTags: Record<string, 'info' | 'warning' | 'success' | 'danger'> = {
  PENDING: 'info',
  PROCESSING: 'warning',
  RUNNING: 'warning',
  SUCCEEDED: 'success',
  FAILED: 'danger',
}

/**
 * 受理环超时态的词（12 的 `UI-F02`：最长 60 秒，超时转「稍后查看」）。
 * 它不是后端的受理状态枚举值，只用于前端展示，故不并入 `acceptanceStatusLabels`。
 */
export const acceptanceTimeoutLabel = '稍后查看'

/** 数据域维度，闭集见契约枚举 E-14。 */
export const scopeDimensions = ['OWNER', 'WAREHOUSE'] as const
export type ScopeDimension = (typeof scopeDimensions)[number]

export const scopeDimensionLabels: Record<ScopeDimension, string> = {
  OWNER: '货主',
  WAREHOUSE: '仓',
}

/** 数据域范围类型，闭集见契约枚举 E-13，只有全部与指定列表两种。 */
export const scopeTypeLabels: Record<'ALL' | 'LIST', string> = {
  ALL: '全部',
  LIST: '指定列表',
}

export const clientLabels: Record<string, string> = {
  HUB: '运营端',
  SITE: '单仓端',
  MOBILE: '移动作业端',
  OWNER: '货主端',
}

/**
 * 商品档案状态词。取值见变更 `add-three-end-product-archive` 的 design 契约章「枚举约定」
 * （`DRAFT`／`PENDING`／`ACTIVE`／`REJECTED`／`DISABLED`），中文词按 `ui.md`「商品档案状态词统一为
 * 草稿、待审核、启用、驳回、停用」，一个枚举值只对应一个词（`UI-W05`）。
 * 语义色按 `UI-B10` 四组：待审核＝进行中，启用＝已完成，驳回＝异常，草稿与停用＝终止。
 */
export const archiveStatusLabels: Record<string, string> = {
  DRAFT: '草稿',
  PENDING: '待审核',
  ACTIVE: '启用',
  REJECTED: '驳回',
  DISABLED: '停用',
}

export const archiveStatusTags: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
  DRAFT: 'info',
  PENDING: 'warning',
  ACTIVE: 'success',
  REJECTED: 'danger',
  DISABLED: 'info',
}

/** 档案状态下拉的可选值，顺序与状态机推进一致，页面不再各写一份。 */
export const archiveStatusOptions = ['DRAFT', 'PENDING', 'ACTIVE', 'REJECTED', 'DISABLED'] as const

/**
 * 单仓变更申请状态词。取值见契约「字段结构与枚举」：变更申请状态枚举为
 * `PENDING`、`APPROVED`、`REJECTED`，一个枚举值只对应一个词（`UI-W05`）。
 */
export const changeRequestStatusLabels: Record<string, string> = {
  PENDING: '待审核',
  APPROVED: '已通过',
  REJECTED: '已驳回',
}

export const changeRequestStatusTags: Record<string, 'success' | 'info' | 'warning' | 'danger'> = {
  PENDING: 'warning',
  APPROVED: 'success',
  REJECTED: 'danger',
}

/**
 * 审核与批量动作词，取值与契约「动作通过或驳回」及 API-009 的批量动作闭集对应
 * （`APPROVE`、`REJECT`、`DISABLE`）；动作词按 `UI-W01` 收口为「通过」「驳回」「停用」。
 */
export const reviewActionLabels: Record<string, string> = {
  APPROVE: '通过',
  REJECT: '驳回',
  DISABLE: '停用',
}

/**
 * 详情分区标题与顺序，逐字对应 `ui.md`「分区顺序为基础信息、计量包装、保管扩展、追溯控制、审核记录」，
 * 导航项与分区标题同源，保证 `UI-D03` 的逐字一致。
 */
export const archiveSectionTitles = {
  basic: '基础信息',
  packaging: '计量包装',
  custody: '保管扩展',
  traceability: '追溯控制',
  review: '审核记录',
} as const

/**
 * 档案字段的中文标签，只此一份（`UI-W19` 术语固定、`UI-W05` 一个字段一个词）。
 * 键取契约「字段结构与枚举」的英文名：`archiveId` 系统编号、`ownerCode` 货主系统编码。
 */
export const archiveFieldLabels: Record<string, string> = {
  archiveId: '系统编号',
  ownerId: '归属货主',
  ownerCode: '货主系统编码',
  siteId: '本仓',
  name: '名称',
  barcode: '条码',
  barcodeType: '条码类型',
  baseUnit: '基本单位',
  storageCondition: '保管条件',
  category: '品类',
  spec: '规格型号',
  brand: '品牌',
  packageSpec: '包装规格',
  packageConversionRate: '包装换算率',
  netWeight: '净重',
  grossWeight: '毛重',
  volume: '体积',
  length: '长',
  width: '宽',
  height: '高',
  highValue: '高值标记',
  hazardous: '危化标记',
  fragile: '易碎标记',
  batchManaged: '是否管批次',
  serialManaged: '是否管序列号',
  shelfLifeDays: '标准保质期',
  remark: '备注',
  status: '状态',
  version: '版本号',
  createdAt: '创建时间',
  updatedAt: '更新时间',
  reviewReason: '驳回原因',
  disableReason: '停用原因',
}

/**
 * 单仓端允许发起变更的主数据字段闭集。取值逐字对齐契约字段表 `changeField`：
 * `name`、`spec`、`category`、`storageCondition`；`baseUnit` 不在闭集内，单仓不提供
 * 基本单位变更入口。不含库位关联字段与部署信息。
 * 中文词取 `archiveFieldLabels`，保证同一字段全系统只有一个词（`UI-W05`）。
 */
export const archiveChangeFields: Array<{ code: string; label: string }> = [
  'name',
  'spec',
  'category',
  'storageCondition',
].map((code) => ({ code, label: archiveFieldLabels[code] }))

export const archiveChangeFieldLabels: Record<string, string> = Object.fromEntries(
  archiveChangeFields.map((item) => [item.code, item.label]),
)

/** 布尔字段的展示词（`UI-W05`：一个值一个词，不在页面里各写一套）。 */
export const booleanLabels: Record<'true' | 'false', string> = {
  true: '是',
  false: '否',
}

/**
 * 变更申请字段的中文标签，只此一份；键取契约字段表的英文名
 * （申请编号 `requestNo`、审核驳回原因 `reviewReason`、本仓 `siteId`、变更字段 `changeField`）。
 * 契约响应里没有档案名称、审核人等字段，因此不在此登记对应词条，页面也不按这些名字取值。
 */
export const changeRequestFieldLabels: Record<string, string> = {
  requestNo: '申请编号',
  archiveId: '档案编号',
  ownerId: '归属货主',
  siteId: '本仓',
  changeField: '变更字段',
  targetValue: '目标值',
  reason: '原因',
  status: '状态',
  reviewReason: '驳回原因',
  createdAt: '提交时间',
  updatedAt: '更新时间',
}

/**
 * 列表排序字段的选项，取值闭集见契约字段表 `sortBy`（`updateTime`、`ownerCode`、`name`）。
 * 排序值按 `UI-X04` 与筛选条件一起写进地址栏，页面不再各写一份选项。
 */
export const archiveSortOptions: Array<{ value: string; label: string }> = [
  { value: 'updateTime', label: '更新时间' },
  { value: 'ownerCode', label: '货主系统编码' },
  { value: 'name', label: '名称' },
]

/** 排序方向选项，取值闭集见契约字段表 `sortDir`，缺省 `desc`。 */
export const sortDirOptions: Array<{ value: string; label: string }> = [
  { value: 'desc', label: '降序' },
  { value: 'asc', label: '升序' },
]
