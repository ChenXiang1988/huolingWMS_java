<script setup lang="ts">
import { onUnmounted, watch } from 'vue'
import { useSessionStore } from './stores/session'
import SessionGate from './components/SessionGate.vue'

/**
 * 单仓端根壳。12a「单仓端 Web 补充」要求触摸优先、控件大，
 * 这里统一把控件档位提到 `large`（Element Plus 的四档之一，不自造尺寸），页面不再逐个声明。
 *
 * 未换到令牌时先渲染会话门（API-018），换到后再渲染路由视图；
 * 到期前 5 分钟按 API-019 续期（`UI-X05`），每分钟检查一次，页面不各自计时。
 */
const session = useSessionStore()

const RENEW_CHECK_MS = 60_000
let timer: number | undefined

function stopRenew() {
  if (timer) {
    window.clearInterval(timer)
    timer = undefined
  }
}

watch(
  () => session.established,
  (established) => {
    stopRenew()
    if (!established) {
      return
    }
    // 进入应用先按到期距离判断一次，随后每分钟检查（UI-X05：剩余 5 分钟续期）
    void session.ensureFresh()
    timer = window.setInterval(() => void session.ensureFresh(), RENEW_CHECK_MS)
  },
  { immediate: true },
)

onUnmounted(stopRenew)
</script>

<template>
  <el-config-provider size="large">
    <SessionGate v-if="!session.established" />
    <router-view v-else />
  </el-config-provider>
</template>
