<script setup lang="ts">
import { computed } from 'vue'
import { formatListTime } from './format'
import ActionButton from './ActionButton.vue'

/**
 * 降级数据提示条（12 的 `UI-S05`、`UI-S07`），三端列表页共用一份，不按页另写皮肤。
 *
 * - `UI-S05`：顶部提示条，警告色，主文案只有「数据时间：YYYY-MM-DD HH:mm」，出口「刷新取实时」；
 * - `UI-S07`：常驻到刷新成功，不自动消失；同页多个降级数据源只显示一条，取最早的数据时间。
 *
 * 提示条只在至少一个数据源带 `dataTimestamp` 时出现，不把缓存数据伪装成实时结果（D19）。
 * `hint` 是可选的补充说明，不传就只留 `UI-S05` 要求的数据时间与出口，文案不在此新写。
 */
const props = defineProps<{
  /**
   * 本页各降级数据源的数据时间（列表查询、受理查询各一项）。
   * 空值表示该源本次未降级；全部为空时不渲染提示条。
   */
  sources: Array<string | null | undefined>
  /** 提示条内的补充说明，取值由承载页提供。 */
  hint?: string
}>()

const emit = defineEmits<{
  (e: 'refresh'): void
}>()

/** UI-S07：同页多个降级数据源只显示一条，取最早的数据时间。 */
const dataTimestamp = computed(() => {
  const values = props.sources.filter((value): value is string => !!value)
  if (values.length === 0) {
    return null
  }
  return values.reduce((earliest, value) => {
    const earliestAt = Date.parse(earliest)
    const valueAt = Date.parse(value)
    if (Number.isNaN(earliestAt)) {
      return Number.isNaN(valueAt) ? earliest : value
    }
    if (Number.isNaN(valueAt)) {
      return earliest
    }
    return valueAt < earliestAt ? value : earliest
  })
})
</script>

<template>
  <!-- UI-S05：顶部提示条、警告色，主文案只写数据时间；UI-S07：常驻到刷新成功 -->
  <el-alert
    v-if="dataTimestamp"
    type="warning"
    :closable="false"
    show-icon
    :title="`数据时间：${formatListTime(dataTimestamp)}`"
    class="degraded-banner"
  >
    <template #default>
      <div class="degraded-line">
        <span v-if="hint">{{ hint }}</span>
        <!-- UI-S05：降级数据的唯一出口是刷新取实时 -->
        <ActionButton variant="text" @click="emit('refresh')">刷新取实时</ActionButton>
      </div>
    </template>
  </el-alert>
</template>

<style scoped>
/* UI-S05：提示条与下方内容间距 16px（4 的倍数，UI-T14） */
.degraded-banner {
  margin-bottom: 16px;
}
/* UI-S05：说明与出口同一行；间距由 ActionButton 的相邻规则承担，此处不再叠加（UI-T14） */
.degraded-line {
  display: flex;
  align-items: center;
}
</style>
