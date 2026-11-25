<script setup lang="ts">
import { computed } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ActionButton } from '@wms/design'
import { PERMISSION_CONTACT_ROLE } from '../permissions'

/**
 * 无权限页（`UI-S03`）：整页说明 + 需联系的角色 + 返回出口。
 * 跨仓越权与缺功能权限都由服务端判，这里只展示（`UI-S03`、design「鉴权」）。
 */
const route = useRoute()
const router = useRouter()

const missingPermission = computed(() =>
  typeof route.query.permission === 'string' && route.query.permission ? route.query.permission : '',
)

function back() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  router.push('/site/archives')
}
</script>

<template>
  <el-result icon="warning" title="无权访问">
    <template #sub-title>
      <p class="forbidden-line">当前账号没有打开该页面的权限，或该数据不在本仓范围内。</p>
      <p v-if="missingPermission" class="forbidden-line">
        所需权限：<code class="forbidden-code">{{ missingPermission }}</code>
      </p>
      <p class="forbidden-line">如需开通，请联系{{ PERMISSION_CONTACT_ROLE }}分配对应角色。</p>
    </template>
    <template #extra>
      <ActionButton variant="primary" @click="back">返回上一页</ActionButton>
    </template>
  </el-result>
</template>

<style scoped>
.forbidden-line {
  margin: 0 0 8px;
  font-size: 16px;
  color: var(--el-text-color-secondary);
}
.forbidden-code {
  padding: 0 4px;
  font-family: var(--el-font-family-mono, monospace);
  color: var(--el-text-color-primary);
}
</style>
