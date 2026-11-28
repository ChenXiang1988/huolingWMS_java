<script setup lang="ts">
import { computed } from 'vue'
import { useRouter } from 'vue-router'
import { ActionButton, PageHeader, formatListTime } from '@wms/design'
import { useAuthStore } from '../stores/auth'
import { canVisit } from '../permissions'
import { HOME_PATH, moduleIcon } from '../router/navigation'

/**
 * 工作台（`UI-P14`）：页头加三张信息卡，数据全部取自登录态内存态，
 * 不额外请求接口，也不虚构待办数、库存量这类业务指标。
 */
const auth = useAuthStore()
const router = useRouter()

const profile = computed(() => auth.profile)

/** 角色列表；未分配时给一句说明，不显示空占位。 */
const rolesText = computed(() => profile.value?.roles.join('、') || '未分配角色')

/** 会话到期时间来自登录态，未知时按「—」展示，不编造时间。 */
const expiresText = computed(() =>
  auth.expiresAt === null ? '—' : formatListTime(new Date(auth.expiresAt).toISOString()),
)

/**
 * 数据域：全量显示「全部货主」「全部仓」，非全量按数量展示（`UI-P14`）。
 * 登录态只带标识、不带名称，因此不拼标识列表，也不为取名称新增请求。
 */
const ownerScope = computed(() => {
  const scope = profile.value?.dataScope
  if (!scope) {
    return '—'
  }
  return scope.allOwners ? '全部货主' : `${scope.ownerIds.length} 个货主`
})

const warehouseScope = computed(() => {
  const scope = profile.value?.dataScope
  if (!scope) {
    return '—'
  }
  return scope.allWarehouses ? '全部仓' : `${scope.warehouseIds.length} 个仓`
})

/**
 * 功能入口 = 路由表 ∩ 权限集，去掉工作台自身（`UI-P14`）。
 * 路由元数据只声明所属模块，入口图标取该模块的图标，页面不再各带一份。
 */
const entries = computed(() =>
  router
    .getRoutes()
    .filter(
      (item) =>
        item.meta.menu && item.path !== HOME_PATH && canVisit(item.meta, (code) => auth.hasPermission(code)),
    )
    .map((item) => ({
      path: item.path,
      title: item.meta.title ?? item.path,
      icon: moduleIcon(item.meta.group),
    })),
)

function open(path: string) {
  router.push(path)
}
</script>

<template>
  <div>
    <PageHeader title="工作台" show-date />

    <div class="work-cards">
      <el-card class="work-card">
        <template #header>当前账号</template>
        <p v-if="!profile" class="work-empty">暂未获取到账号信息</p>
        <dl v-else class="work-list">
          <div class="work-row">
            <dt>账号</dt>
            <dd>{{ profile.accountName }}</dd>
          </div>
          <div class="work-row">
            <dt>角色</dt>
            <dd>{{ rolesText }}</dd>
          </div>
          <div class="work-row">
            <dt>会话到期时间</dt>
            <dd>{{ expiresText }}</dd>
          </div>
        </dl>
      </el-card>

      <el-card class="work-card">
        <template #header>数据域</template>
        <p v-if="!profile" class="work-empty">暂未获取到数据域</p>
        <dl v-else class="work-list">
          <div class="work-row">
            <dt>货主范围</dt>
            <dd>{{ ownerScope }}</dd>
          </div>
          <div class="work-row">
            <dt>仓范围</dt>
            <dd>{{ warehouseScope }}</dd>
          </div>
        </dl>
      </el-card>

      <el-card class="work-card">
        <template #header>功能入口</template>
        <p v-if="entries.length === 0" class="work-empty">当前账号除工作台外没有可进入的页面</p>
        <div v-else class="work-entries">
          <ActionButton
            v-for="entry in entries"
            :key="entry.path"
            variant="text"
            @click="open(entry.path)"
          >
            <el-icon v-if="entry.icon" class="work-entry-icon"><component :is="entry.icon" /></el-icon>
            {{ entry.title }}
          </ActionButton>
        </div>
      </el-card>
    </div>
  </div>
</template>

<style scoped>
/* UI-P14：卡间距 16，视口窄于 1280 时折为单列 */
.work-cards {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}

@media (max-width: 1279px) {
  .work-cards {
    grid-template-columns: minmax(0, 1fr);
  }
}

.work-list {
  margin: 0;
}

/* UI-T12：正文 14；UI-T13：行高 1.5 */
.work-row {
  display: flex;
  gap: 12px;
  padding: 4px 0;
  font-size: 14px;
  line-height: 1.5;
}

.work-row dt {
  flex-shrink: 0;
  width: 96px;
  color: var(--el-text-color-secondary);
}

.work-row dd {
  margin: 0;
  min-width: 0;
  word-break: break-all;
}

.work-empty {
  margin: 0;
  font-size: 13px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.work-entries {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
}

/* 入口纵向排列，抵消 ActionButton 的相邻按钮左边距 */
.work-entries :deep(.action-button + .action-button) {
  margin-left: 0;
}

.work-entry-icon {
  margin-right: 8px;
  font-size: 16px;
}
</style>
