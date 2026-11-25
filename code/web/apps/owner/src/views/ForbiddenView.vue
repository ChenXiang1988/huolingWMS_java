<script setup lang="ts">
import { useRouter } from 'vue-router'
import { ActionButton } from '@wms/design'

/**
 * 无权限页（`UI-S03`）：整页说明 + 需联系的角色 + 返回出口。
 * 只负责展示，不重新判定权限（判定在守卫与服务端）。
 * 货主端不出现内部术语、权限点编码与错误码原文（12a「货主端补充」、`UI-P05`），
 * 故这里只说明「没有打开该页面的权限」，不打印所需权限点。
 */
const router = useRouter()

function back() {
  if (window.history.state?.back) {
    router.back()
    return
  }
  router.push('/owner/archives')
}
</script>

<template>
  <el-result icon="warning" title="无权访问">
    <template #sub-title>
      <p class="forbidden-line">当前账号没有打开该页面的权限。</p>
      <p class="forbidden-line">如需开通，请联系为您开通账号的业务对接人分配对应权限。</p>
    </template>
    <template #extra>
      <ActionButton variant="primary" @click="back">返回上一页</ActionButton>
    </template>
  </el-result>
</template>

<style scoped>
.forbidden-line {
  margin: 0 0 8px;
  font-size: 14px;
  color: var(--el-text-color-secondary);
}
</style>

