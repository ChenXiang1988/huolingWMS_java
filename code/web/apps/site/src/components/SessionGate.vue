<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { ActionButton, FormField, FormInput } from '@wms/design'
import { ApiError, describeApiError } from '@wms/request'
import { useSessionStore } from '../stores/session'

/**
 * 会话门：进入应用先按 API-018 换取站点令牌、仓标识与功能权限列表
 * （`ui.md`「单仓端 会话获取与续期」）。它不是独立路由页，路由表只保留 design 页面清单里的两条；
 * 换取成功后由根壳切到路由视图。
 *
 * 凭证错误、端标识错误与多仓／无仓声明都由服务端拒绝并返回 `FORBIDDEN_SITE`，按 `UI-S03` 处理：
 * 不进入任何档案数据，页面给出错误码（`UI-S06`）与重新获取入口（`UI-I06`），不在前端自行判定仓数。
 */
const session = useSessionStore()

const form = reactive({ siteCode: '', credential: '' })
const error = ref('')
const errorCode = ref('')
const submitting = computed(() => session.acquiring)

async function submit() {
  if (submitting.value) {
    return
  }
  error.value = ''
  errorCode.value = ''
  if (!form.siteCode.trim()) {
    error.value = '站点编码必填'
    return
  }
  if (!form.credential) {
    error.value = '站点凭证必填'
    return
  }
  try {
    await session.login(form.siteCode, form.credential)
    // `credential` 只在换取令牌时使用；刷新凭证由 API-018 以 httpOnly cookie 下发，
    // 前端不读不存，故成功后清空输入（见 stores/session.ts）。
    form.credential = ''
  } catch (e) {
    error.value = describeApiError(e)
    errorCode.value = e instanceof ApiError ? String(e.code) : ''
  }
}
</script>

<template>
  <div class="session-gate">
    <el-card class="session-gate__card">
      <template #header>
        <span>货灵 WMS · 单仓端</span>
      </template>

      <el-alert v-if="error" type="error" :closable="false" show-icon :title="error" style="margin-bottom: 16px">
        <template #default>
          <span v-if="errorCode">错误码 {{ errorCode }}</span>
        </template>
      </el-alert>

      <el-form label-width="100px">
        <FormField label="站点编码" required>
          <FormInput v-model="form.siteCode" placeholder="本仓站点编码" @keyup.enter="submit" />
        </FormField>
        <FormField label="站点凭证" required>
          <el-input
            v-model="form.credential"
            type="password"
            show-password
            placeholder="站点凭证"
            @keyup.enter="submit"
          />
        </FormField>
      </el-form>

      <div class="session-gate__actions">
        <ActionButton variant="primary" :loading="submitting" @click="submit">获取会话</ActionButton>
      </div>
    </el-card>
  </div>
</template>

<style scoped>
/* UI-P04：内容区居中；卡片宽度取 12 的弹窗最窄档，控件不铺满整屏 */
.session-gate {
  display: flex;
  align-items: center;
  justify-content: center;
  min-height: 100vh;
  padding: 24px;
  box-sizing: border-box;
}
.session-gate__card {
  width: 480px;
  max-width: 100%;
}
/* UI-C07：主操作右对齐 */
.session-gate__actions {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}
</style>
