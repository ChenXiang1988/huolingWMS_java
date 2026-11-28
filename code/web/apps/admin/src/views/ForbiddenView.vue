<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ActionButton } from '@wms/design'
import { PERMISSION_CONTACT_ROLE } from '../permissions'

/**
 * 无权限页（`UI-S03`）：整页说明 + 需联系的角色 + 返回出口。
 * 守卫在缺权限点时把所需权限点与原地址带进地址栏，这里只负责展示，不重新判定权限。
 */
const route = useRoute()
const router = useRouter()

const missingPermission = computed(() =>
  typeof route.query.permission === 'string' && route.query.permission ? route.query.permission : '',
)

function backToWorkbench() {
  router.push('/home')
}

/** 上一页不存在时（直接输地址进来）退回工作台，避免退到站外。 */
function back() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  backToWorkbench()
}
</script>

<template>
  <!-- 外层统一为容器级底（UI-T20），文案与跳转保持不变 -->
  <el-card>
    <el-result icon="warning" title="无权访问">
      <template #sub-title>
        <p class="forbidden-line">
          当前账号缺少权限点：
          <code class="forbidden-code">{{ missingPermission || '该页面所需权限点' }}</code>
        </p>
        <p class="forbidden-line">如需开通，请联系{{ PERMISSION_CONTACT_ROLE }}分配对应角色。</p>
      </template>
      <template #extra>
        <ActionButton variant="primary" @click="backToWorkbench">返回工作台</ActionButton>
        <ActionButton variant="secondary" @click="back">返回上一页</ActionButton>
      </template>
    </el-result>
  </el-card>
</template>

<style scoped>
.forbidden-line {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
.forbidden-code {
  padding: 0 4px;
  font-family: var(--el-font-family-mono, monospace);
  color: var(--el-text-color-primary);
}
</style>
