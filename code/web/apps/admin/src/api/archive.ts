/**
 * 运营端商品档案的接口装配与详情分区构造。
 *
 * 接口路径、方法、字段与错误码来自 `@wms/request` 的契约声明
 * （API-006~API-009、API-012、API-013、API-016、API-020），
 * 本文件只做三件事：把接口绑到当前会话的请求层、把代建归属接到货主选择器、
 * 把档案条目映射成详情抽屉的分区。
 * 分区顺序按 `ui.md`「分区顺序为基础信息、计量包装、保管扩展、追溯控制、审核记录」，
 * 没有的分区跳过但不重排（`UI-D05`）。
 */
import { archiveFieldLabels, archiveSectionTitles, booleanLabels } from '@wms/dict'
import { formatDetailTime } from '@wms/design'
import { createHubArchiveApi, createHubOwnerApi, type ArchiveItem } from '@wms/request'
import { useAuthStore } from '../stores/auth'

/** 运营端档案接口，绑定当前会话（令牌与续期由请求层承担）。 */
export function useHubArchiveApi() {
  return createHubArchiveApi(useAuthStore().http())
}

/**
 * API-020 货主选择器：代建归属必须从服务端给出的货主里选，
 * 不再用文本输入手填归属标识（`ui.md`「运营端 代建归属选择」）。
 */
export function useHubOwnerApi() {
  return createHubOwnerApi(useAuthStore().http())
}

/**
 * API-016 受理结果查询转成 `AcceptPanel` 的入参形状：受理状态、失败错误码、`result` 摘要
 * 与降级数据时间 `dataTimestamp`（`UI-S05`）。字段名逐字取契约，只在 `acceptStatus` 处改名；
 * 批量逐条结果在 `result.items`，由审核页按 `UI-I08` 展开。
 * 轮询节奏（每 3 秒、最长 60 秒）由 `AcceptPanel` 按 `UI-F02` 承担，页面不另设节奏。
 */
export function toAcceptancePoll(api: ReturnType<typeof useHubArchiveApi>) {
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

/** 详情抽屉的分区结构，与 `DetailDrawer` 的 `sections` 入参同形。 */
export interface DetailSection {
  title: string
  fields: Array<{ label: string; value: string }>
}

/** 字段值转展示串：空值统一「—」，布尔按字典取「是/否」，不在页面里各写一套。 */
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
 * 档案详情分区。`includeOwner` 为 true 时在基础信息里给出归属货主，
 * 运营端看全量需要它；单仓端与货主端不传，各自的分区在此结构上取子集。
 */
export function buildArchiveSections(item: ArchiveItem, includeOwner = true): DetailSection[] {
  const basic = [
    ...(includeOwner ? [field('ownerId', item.ownerId)] : []),
    field('ownerCode', item.ownerCode),
    field('name', item.name),
    field('barcode', item.barcode),
    field('barcodeType', item.barcodeType),
    field('baseUnit', item.baseUnit),
    field('storageCondition', item.storageCondition),
    field('category', item.category),
    field('version', item.version),
  ]

  const packaging = [
    field('packageSpec', item.packageSpec),
    field('packageConversionRate', item.packageConversionRate),
    field('netWeight', item.netWeight),
    field('grossWeight', item.grossWeight),
    field('volume', item.volume),
    field('length', item.length),
    field('width', item.width),
    field('height', item.height),
  ]

  const custody = [field('highValue', item.highValue), field('hazardous', item.hazardous), field('fragile', item.fragile)]

  const traceability = [
    field('batchManaged', item.batchManaged),
    field('serialManaged', item.serialManaged),
    field('shelfLifeDays', item.shelfLifeDays),
  ]

  // 审核记录只放契约响应里有的字段：驳回原因取 `reviewReason`；
  // 契约没有审核人与生效时间，故不在此假设，避免渲染成空值。
  const review = [
    field('reviewReason', item.reviewReason),
    field('createdAt', item.createdAt ? formatDetailTime(item.createdAt) : null),
    field('updatedAt', item.updatedAt ? formatDetailTime(item.updatedAt) : null),
  ]

  return [
    { title: archiveSectionTitles.basic, fields: basic },
    { title: archiveSectionTitles.packaging, fields: packaging },
    { title: archiveSectionTitles.custody, fields: custody },
    { title: archiveSectionTitles.traceability, fields: traceability },
    { title: archiveSectionTitles.review, fields: review },
  ]
}
