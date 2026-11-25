<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ActionButton, AppShell } from '@wms/design'
import { useSessionStore } from '../stores/session'
import { canVisit } from '../permissions'

/**
 * 单仓端外壳。沿用三端共用的 `AppShell`（`UI-P04` 尺寸与画布），不另写一套；
 * 端名由 `brand` 传入。菜单 = 路由表 ∩ 权限集，无权限的项不渲染（`UI-X06`）。
 *
 * 续期失败（API-019 被拒或令牌失效）按 `ui.md`「单仓端 会话获取与续期」处理：
 * 保留当前页，只给一条常驻提示与「重新登录」出口，不整页跳走。
 */
const session = useSessionStore()
const route = useRoute()
const router = useRouter()

const menus = computed(() =>
  router
    .getRoutes()
    .filter((item) => item.meta.menu && canVisit(item.meta, (code) => session.hasPermission(code)))
    .map((item) => ({ path: item.path, title: item.meta.title as string })),
)

/** 重新登录：清空内存态后由根壳切回会话门，按 API-018 重新换取（当前路由不丢）。 */
function relogin() {
  session.clear()
}
</script>

<template>
  <AppShell :menus="menus" :active="route.path" brand="货灵 WMS · 单仓端">
    <el-alert
      v-if="session.renewFailed"
      type="warning"
      :closable="false"
      show-icon
      title="会话续期失败：当前页可继续查看，重新登录后再提交。"
      style="margin-bottom: 16px"
    >
      <template #default>
        <ActionButton variant="text" @click="relogin">重新登录</ActionButton>
      </template>
    </el-alert>
    <router-view />
  </AppShell>
</template>
