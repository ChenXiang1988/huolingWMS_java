/**
 * 单仓端商品档案与变更申请的接口装配与详情分区构造。
 *
 * 接口路径、方法、字段与错误码来自 `@wms/request` 的契约声明（API-010、API-011、API-014、API-017）。
 * 单仓端只读本仓生效档案，主数据字段不直接修改，只能发起变更申请（design「决策」）。
 * 降级数据必须显示数据时间戳（`UI-S05`、`UI-S07`），本文件只做展示串，不判定新鲜度。
 */
import {
  archiveChangeFieldLabels,
  archiveFieldLabels,
  archiveSectionTitles,
  booleanLabels,
  changeRequestFieldLabels,
  changeRequestStatusLabels,
} from '@wms/dict'
import { formatDetailTime } from '@wms/design'
import { createSiteArchiveApi, type ArchiveItem, type ChangeRequestItem } from '@wms/request'
import { useSessionStore } from '../stores/session'

/** 单仓端接口，令牌取自已换取的站点令牌（API-018）。 */
export function useSiteArchiveApi() {
  return createSiteArchiveApi(useSessionStore().http())
}

/**
 * API-017 受理结果查询转成 `AcceptPanel` 的入参形状：受理状态、失败错误码、`result` 摘要
 * 与降级数据时间 `dataTimestamp`（`UI-S05`、`UI-S07`）。字段名逐字取契约，只在 `acceptStatus` 处改名。
 * 轮询节奏（每 3 秒、最长 60 秒）由 `AcceptPanel` 按 `UI-F02` 承担，页面不另设节奏。
 */
export function toAcceptancePoll(api: ReturnType<typeof useSiteArchiveApi>) {
  return async (acceptNo: string) => {
    const data = await api.getAcceptance(acceptNo)
    return {
      status: data.acceptStatus,
      errorCode: data.errorCode ?? null,
      result: data.result ?? null,
      dataTimestamp: data.dataTimestamp ?? null,
    }
  }
}

export interface DetailSection {
  title: string
  fields: Array<{ label: string; value: string }>
}

/** 字段值转展示串：空值统一「—」，布尔按字典取「是/否」。 */
export function fieldText(value: string | number | boolean | null | undefined): string {
  if (value === null || value === undefined || value === '') {
    return '—'
  }
  if (typeof value === 'boolean') {
    return booleanLabels[value ? 'true' : 'false']
  }
  return String(value)
}

function field(key: string, value: string | number | boolean | null | undefined) {
  return { label: archiveFieldLabels[key] ?? key, value: fieldText(value) }
}

/**
 * 本仓档案详情分区。顺序按 `UI-D05`：基础信息 → 计量包装 → 保管扩展 → 追溯控制；
 * 单仓端不展示审核记录（审核留痕归运营端），也不出现库位关联字段（design「单仓维护」）。
 */
export function buildArchiveSections(item: ArchiveItem): DetailSection[] {
  return [
    {
      title: archiveSectionTitles.basic,
      fields: [
        field('ownerCode', item.ownerCode),
        field('name', item.name),
        field('barcode', item.barcode),
        field('barcodeType', item.barcodeType),
        field('baseUnit', item.baseUnit),
        field('storageCondition', item.storageCondition),
        field('category', item.category),
        field('version', item.version),
      ],
    },
    {
      title: archiveSectionTitles.packaging,
      fields: [
        field('packageSpec', item.packageSpec),
        field('packageConversionRate', item.packageConversionRate),
        field('netWeight', item.netWeight),
        field('grossWeight', item.grossWeight),
        field('volume', item.volume),
        field('length', item.length),
        field('width', item.width),
        field('height', item.height),
      ],
    },
    {
      title: archiveSectionTitles.custody,
      fields: [field('highValue', item.highValue), field('hazardous', item.hazardous), field('fragile', item.fragile)],
    },
    {
      title: archiveSectionTitles.traceability,
      fields: [
        field('batchManaged', item.batchManaged),
        field('serialManaged', item.serialManaged),
        field('shelfLifeDays', item.shelfLifeDays),
        field('remark', item.remark),
      ],
    },
  ]
}

/**
 * 变更申请详情分区：申请信息 → 变更内容 → 审核记录（`UI-D05`）。
 * 字段名逐字取契约响应：申请编号 `requestNo`、本仓 `siteId`、驳回原因 `reviewReason`；
 * 契约没有档案名称、审核人，故不展示这些字段，免成空值。
 */
export function buildChangeRequestSections(item: ChangeRequestItem): DetailSection[] {
  return [
    {
      title: '申请信息',
      fields: [
        { label: changeRequestFieldLabels.requestNo, value: fieldText(item.requestNo) },
        { label: changeRequestFieldLabels.archiveId, value: fieldText(item.archiveId) },
        { label: changeRequestFieldLabels.ownerId, value: fieldText(item.ownerId) },
        { label: changeRequestFieldLabels.siteId, value: fieldText(item.siteId) },
        { label: changeRequestFieldLabels.createdAt, value: formatDetailTime(item.createdAt) },
      ],
    },
    {
      title: '变更内容',
      fields: [
        { label: changeRequestFieldLabels.changeField, value: archiveChangeFieldLabels[item.changeField] ?? item.changeField },
        { label: changeRequestFieldLabels.targetValue, value: fieldText(item.targetValue) },
        { label: changeRequestFieldLabels.reason, value: fieldText(item.reason) },
      ],
    },
    {
      title: '审核记录',
      fields: [
        { label: changeRequestFieldLabels.status, value: changeRequestStatusLabels[item.status] ?? item.status },
        { label: changeRequestFieldLabels.reviewReason, value: fieldText(item.reviewReason) },
        { label: changeRequestFieldLabels.updatedAt, value: formatDetailTime(item.updatedAt) },
      ],
    },
  ]
}
