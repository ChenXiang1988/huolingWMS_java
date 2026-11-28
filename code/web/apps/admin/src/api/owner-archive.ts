/**
 * 运营端货主档案的接口装配、详情分区与受理失败分派。
 *
 * 路径、字段与错误码来自 `@wms/request` 的契约声明（API-021~API-027），
 * 本文件只做四件事：把接口绑到当前会话、把受理结果转成 `AcceptPanel` 的入参、
 * 把详情对象映射成三段分区、把 `OA-10` 的失败分派表集中一处（新建页、编辑页、列表页共用）。
 *
 * 分区顺序按 `ui.md`「OA-02」：基础信息 → 状态变更 → 操作记录，没有的分区跳过但不重排（`UI-D05`）。
 * 字段标签取 `@wms/dict` 的 `ownerFieldLabels`，页面不各自命名。
 */
import {
  archiveErrorHints,
  createHubOwnerArchiveApi,
  type OwnerAcceptanceOutcome,
  type OwnerArchiveDetail,
  type OwnerStatusChangeBody,
} from '@wms/request'
import { formatDetailTime } from '@wms/design'
import type {
  AcceptanceExitView,
  AcceptanceFailureView,
  AcceptancePollResult,
  AcceptanceResultView,
} from '@wms/design'
import {
  ownerFieldLabels,
  ownerSectionTitles,
  ownerStatusChangeActionLabels,
  ownerStatusLabels,
} from '@wms/dict'
import { fieldText, type DetailSection } from './archive'
import { useAuthStore } from '../stores/auth'

/** 运营端货主档案接口，绑定当前会话（令牌与续期由请求层承担）。 */
export function useHubOwnerArchiveApi() {
  return createHubOwnerArchiveApi(useAuthStore().http())
}

/**
 * API-027 转成 `AcceptPanel` 的入参形状：受理状态、失败错误码与说明、`result` 摘要。
 * 字段名逐字取契约，只在 `acceptStatus` 处改名；轮询节奏（首发 1 秒、此后 3 秒、最长 60 秒）
 * 由 `AcceptPanel` 按 `OA-09` 承担，页面不另设节奏。
 * 契约不读副本，成功响应不带 `dataTimestamp`，故不传降级数据时间（`design.md`「异常与空态」）。
 */
export function toOwnerAcceptancePoll(api: ReturnType<typeof useHubOwnerArchiveApi>) {
  return async (acceptNo: string): Promise<AcceptancePollResult> => {
    const data: OwnerAcceptanceOutcome = await api.getAcceptance(acceptNo)
    return {
      status: data.acceptStatus,
      errorCode: data.errorCode ?? null,
      errorMessage: data.errorMessage ?? null,
      result: data.result ?? null,
    }
  }
}

/** 结果摘要的单行文案（`OA-09`：成功时展示货主编号、状态、版本），词取自字典。 */
export function ownerResultSummary(result: AcceptanceResultView): string {
  const parts: string[] = []
  if (result.ownerNo) {
    parts.push(`货主编号 ${result.ownerNo}`)
  }
  if (result.status) {
    parts.push(`状态 ${ownerStatusLabels[result.status] ?? result.status}`)
  }
  if (typeof result.version === 'number') {
    parts.push(`版本 ${result.version}`)
  }
  return parts.join('，')
}

function field(key: string, value: string | number | null | undefined) {
  return { label: ownerFieldLabels[key] ?? key, value: fieldText(value) }
}

/**
 * 详情抽屉的三段分区（`OA-02`）。
 * 状态变更只放契约返回的最近一次变更（不画时间线，`UI-C17` 不适用）；
 * 操作记录在从未修改过时只显示创建信息（`UI-B09`）。
 */
export function buildOwnerSections(detail: OwnerArchiveDetail): DetailSection[] {
  const change = detail.lastStatusChange ?? null
  const basic = [
    field('ownerNo', detail.ownerNo),
    field('ownerName', detail.ownerName),
    field('contactName', detail.contactName),
    field('contactPhone', detail.contactPhone),
    field('creditCode', detail.creditCode),
    field('status', ownerStatusLabels[detail.status] ?? detail.status),
    field('version', detail.version),
  ]

  const statusChange = [
    field('action', change ? (ownerStatusChangeActionLabels[change.action] ?? change.action) : null),
    field('reason', change?.reason),
    field('operator', change?.operator),
    field('changedAt', change?.changedAt ? formatDetailTime(change.changedAt) : null),
  ]

  const audit = [
    field('createdBy', detail.createdBy),
    field('createdTime', detail.createdTime ? formatDetailTime(detail.createdTime) : null),
    // 未修改过时只显示创建信息（UI-B09），不补「—」占位的修改人两行
    ...(detail.updatedBy
      ? [
          field('updatedBy', detail.updatedBy),
          field('updateTime', detail.updateTime ? formatDetailTime(detail.updateTime) : null),
        ]
      : []),
  ]

  return [
    { title: ownerSectionTitles.basic, fields: basic },
    { title: ownerSectionTitles.statusChange, fields: statusChange },
    { title: ownerSectionTitles.audit, fields: audit },
  ]
}

/**
 * 受理失败的出口编码。页面只按编码分派动作，文案固定在下面的分派表里，
 * 各页不再各写一份错误码分支（`design.md`「受理票据的前端状态机与失败分派」）。
 */
export type OwnerAcceptExitCode =
  | 'editInput'
  | 'filterByCreditCode'
  | 'refresh'
  | 'discard'
  | 'back'
  | 'retry'
  | 'resubmit'

/** 重提时幂等键的处置：`keep` 沿用原键、`new` 换新键、`none` 无重提入口（`OA-10` 表末列）。 */
export type OwnerIdempotencyPolicy = 'keep' | 'new' | 'none'

export interface OwnerAcceptFailure {
  /** 第二段：为什么（取自契约 1.4 表的「调用方处置」）。 */
  message: string
  /** 第三段：下一步怎么办。 */
  nextStep: string
  keyPolicy: OwnerIdempotencyPolicy
  exits: AcceptanceExitView[]
}

/**
 * `OA-10` 的单处分派表：错误码 → 第三段文案、出口与幂等键处置。
 * 只登记 `OA-10` 表里的码；未登记的错误码由受理条回落到服务端 `errorMessage`，
 * 不在此自造文案（`UI-W07` 禁止「操作失败」这类空话）。
 */
const ownerAcceptFailures: Record<string, OwnerAcceptFailure> = {
  RULE_DUPLICATE_CREDIT_CODE: {
    message: archiveErrorHints.RULE_DUPLICATE_CREDIT_CODE,
    nextStep: '核对信用代码是否属于其他主体',
    keyPolicy: 'new',
    exits: [
      { code: 'editInput', label: '修改输入' },
      { code: 'filterByCreditCode', label: '按信用代码筛选列表' },
    ],
  },
  STATE_INVALID: {
    message: archiveErrorHints.STATE_INVALID,
    nextStep: '刷新后按当前状态操作；停用态改档需先启用',
    keyPolicy: 'new',
    exits: [
      { code: 'refresh', label: '刷新查看' },
      { code: 'back', label: '返回列表' },
    ],
  },
  CONFLICT_VERSION: {
    message: archiveErrorHints.CONFLICT_VERSION,
    nextStep: '刷新后按最新数据重新提交',
    keyPolicy: 'new',
    exits: [
      { code: 'refresh', label: '刷新查看' },
      { code: 'discard', label: '放弃本次修改' },
    ],
  },
  NOT_FOUND_OWNER: {
    message: archiveErrorHints.NOT_FOUND_OWNER,
    nextStep: '货主不存在或已不在你的数据域内',
    keyPolicy: 'none',
    exits: [{ code: 'back', label: '返回列表' }],
  },
  DEPENDENCY_UNAVAILABLE: {
    message: archiveErrorHints.DEPENDENCY_UNAVAILABLE,
    nextStep: '稍后重试，不改为查空结果',
    keyPolicy: 'keep',
    exits: [{ code: 'retry', label: '重试' }],
  },
  RATE_LIMITED: {
    message: archiveErrorHints.RATE_LIMITED,
    nextStep: '按提示间隔退避后重试',
    keyPolicy: 'keep',
    exits: [{ code: 'retry', label: '重试' }],
  },
  IDEMPOTENT_IN_PROGRESS: {
    message: archiveErrorHints.IDEMPOTENT_IN_PROGRESS,
    nextStep: '同一请求正在处理中，勿换键',
    keyPolicy: 'keep',
    exits: [{ code: 'retry', label: '重试' }],
  },
  IDEMPOTENT_KEY_CONFLICT: {
    message: archiveErrorHints.IDEMPOTENT_KEY_CONFLICT,
    nextStep: '本次内容与首次提交不一致，请换新键重新提交',
    keyPolicy: 'new',
    exits: [{ code: 'resubmit', label: '重新提交' }],
  },
  PARAM_INVALID: {
    message: archiveErrorHints.PARAM_INVALID,
    nextStep: '按提示修正对应字段后重新提交',
    keyPolicy: 'new',
    exits: [{ code: 'editInput', label: '修改输入' }],
  },
  INTERNAL_ERROR: {
    message: archiveErrorHints.INTERNAL_ERROR,
    // OA-10 的第二个出口是「复制 traceId」；API-027 的响应没有 traceId，见「契约缺口」登记
    nextStep: '带受理号与 traceId 报障',
    keyPolicy: 'new',
    exits: [{ code: 'resubmit', label: '重新提交' }],
  },
  FORBIDDEN_OWNER: {
    message: archiveErrorHints.FORBIDDEN_OWNER,
    nextStep: '换用有权限的账号操作',
    keyPolicy: 'none',
    exits: [{ code: 'back', label: '返回列表' }],
  },
}

/** 受理条要的三段文案与出口；未登记的错误码返回 `null`，由受理条回落到服务端说明。 */
export function describeOwnerAcceptFailure(errorCode: string): AcceptanceFailureView | null {
  const entry = ownerAcceptFailures[errorCode]
  return entry ? { message: entry.message, nextStep: entry.nextStep, exits: entry.exits } : null
}

/** 重提时该用哪个幂等键：只有分派表里标 `keep` 的才沿用原键（`OA-10`）。 */
export function ownerIdempotencyPolicy(errorCode: string | null | undefined): OwnerIdempotencyPolicy {
  if (!errorCode) {
    return 'new'
  }
  return ownerAcceptFailures[errorCode]?.keyPolicy ?? 'new'
}

/**
 * 停用与启用的影响清单，逐字取 `ui.md`「OA-05」「OA-06」，四条逐条成句、不得折叠。
 * 停用与启用共用同一个弹窗外壳，只换动作词、影响清单与接口。
 */
export const ownerDisableImpacts = [
  '停用后：所有仓库以该货主新建的业务将被拒绝',
  '已存在的单据与任务继续执行至终态，不受影响',
  '该货主名下账号立即拒绝登录，已登录会话在下一次请求被拒，数据保留在库',
  '停用不会自动恢复，重新启用只能由具备写权限的人员发起并留痕',
]

export const ownerEnableImpacts = [
  '启用后：所有仓库可再次以该货主新建业务',
  '该货主名下账号可重新登录',
  '货主编号与历史引用不变',
  '停用期间被拒绝的业务不会补做',
]

/** 停用或启用的请求体：`reason` 与 `version` 都必填（契约 API-025、API-026「请求」）。 */
export function ownerStatusChangeBody(reason: string, version: number): OwnerStatusChangeBody {
  return { reason, version }
}
