/**
 * 货主端商品档案的接口装配与详情分区构造。
 *
 * 接口路径、方法、字段与错误码来自 `@wms/request` 的契约声明（API-001~API-005、API-015），
 * 本文件只把接口绑到本端请求层，并把档案条目映射成详情抽屉的分区。
 *
 * 分区顺序按 `ui.md`「分区顺序为基础信息、计量包装、保管扩展、追溯控制、审核记录」；
 * 不出现库位号、单仓数量与部署位置（D17、`UI-P05`），归属货主也不展示（只看自有数据）。
 */
import { archiveFieldLabels, archiveSectionTitles, booleanLabels } from '@wms/dict'
import { formatDetailTime } from '@wms/design'
import { createOwnerArchiveApi, type ArchiveItem } from '@wms/request'
import { useSessionStore } from '../stores/session'

/** 货主端档案接口，绑定平台会话 cookie。 */
export function useOwnerArchiveApi() {
  return createOwnerArchiveApi(useSessionStore().http())
}

/**
 * API-015 受理结果查询转成 `AcceptPanel` 的入参形状：受理状态、失败错误码、`result` 摘要
 * 与降级数据时间 `dataTimestamp`（`UI-S05`）。字段名逐字取契约，只在 `acceptStatus` 处改名。
 * 轮询节奏（每 3 秒、最长 60 秒）由 `AcceptPanel` 按 `UI-F02` 承担，页面不另设节奏。
 */
export function toAcceptancePoll(api: ReturnType<typeof useOwnerArchiveApi>) {
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

/** 货主端档案详情分区，字段集合与运营端一致，但去掉归属货主（只看自有数据）。 */
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
      ],
    },
    {
      title: archiveSectionTitles.review,
      fields: [
        // 驳回原因取契约字段名 `reviewReason`；契约响应里没有审核人与生效时间，故不展示，免成空值。
        field('reviewReason', item.reviewReason),
        field('createdAt', item.createdAt ? formatDetailTime(item.createdAt) : null),
        field('updatedAt', item.updatedAt ? formatDetailTime(item.updatedAt) : null),
        field('remark', item.remark),
      ],
    },
  ]
}
