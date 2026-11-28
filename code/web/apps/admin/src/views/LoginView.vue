<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ActionButton, FormInput } from '@wms/design'
import { ApiError } from '@wms/request'
import { useAuthStore } from '../stores/auth'
import { HOME_PATH } from '../router/navigation'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ accountName: '', password: '' })
const loading = ref(false)
const error = ref('')
/** UI-P15：失败后焦点回密码框，需要控件暴露的焦点句柄。 */
const passwordRef = ref<{ focus: () => void } | null>(null)

/** 左栏品牌区的三条能力说明，逐字取自 `UI-P15`。 */
const capabilities = ['库存可见', '单据可查', '权限可管']

async function submit() {
  if (loading.value) {
    return
  }
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.accountName, form.password)
    const redirect = (route.query.redirect as string) || HOME_PATH
    router.push(redirect)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
    // UI-P15：账号保留、密码清空、焦点回到密码框
    form.password = ''
    passwordRef.value?.focus()
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <!-- UI-P15：左栏宽 480 品牌区，窄于 1280 时隐藏 -->
    <aside class="login-brand">
      <h1 class="login-brand-name">货灵 WMS · 运营后台</h1>
      <!-- UI-P15：端名下方、能力说明上方的一句定位 -->
      <p class="login-brand-slogan">多货主同仓作业，一屏看清库存与单据</p>
      <ul class="login-brand-points">
        <li v-for="item in capabilities" :key="item" class="login-brand-point">{{ item }}</li>
      </ul>
    </aside>

    <main class="login-form-area">
      <el-card class="login-card">
        <h2 class="login-title">登录</h2>
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item label="账号">
            <FormInput v-model="form.accountName" autocomplete="username" />
          </el-form-item>
          <el-form-item label="密码">
            <FormInput
              ref="passwordRef"
              v-model="form.password"
              type="password"
              autocomplete="current-password"
            />
          </el-form-item>
          <!-- UI-P15：错误提示在表单卡内、按钮上方 -->
          <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
          <ActionButton variant="primary" block class="login-submit" :loading="loading" @click="submit">
            {{ loading ? '登录中' : '登录' }}
          </ActionButton>
        </el-form>
      </el-card>
    </main>
  </div>
</template>

<style scoped>
/* UI-T20：品牌区与页面同用页面级底，表单卡取容器级底 */
.login-page {
  display: flex;
  min-height: 100vh;
  background: var(--el-bg-color-page);
}

.login-brand {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 24px;
  width: 480px;
  flex-shrink: 0;
  padding: 48px;
  box-sizing: border-box;
}

.login-brand-name {
  margin: 0;
  font-size: 24px;
  font-weight: 600;
  line-height: 1.5;
}

/* 定位句：字号取 UI-T12 的 16 档，与端名、能力说明拉开层次 */
.login-brand-slogan {
  margin: 0;
  font-size: 16px;
  line-height: 1.5;
  color: var(--el-text-color-regular);
}

.login-brand-points {
  display: flex;
  flex-direction: column;
  gap: 12px;
  margin: 0;
  padding: 0;
  list-style: none;
}

.login-brand-point {
  font-size: 14px;
  line-height: 1.5;
  color: var(--el-text-color-secondary);
}

.login-form-area {
  flex: 1;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  box-sizing: border-box;
}

.login-card {
  width: 400px;
}

/* UI-P15：卡片内边距 32 */
.login-card :deep(.el-card__body) {
  padding: 32px;
}

.login-title {
  margin: 0 0 24px;
  font-size: 20px;
  font-weight: 600;
  line-height: 1.5;
}

.login-submit {
  margin-top: 16px;
}

/* UI-P15：视口窄于 1280 时隐藏品牌区，表单卡居中 */
@media (max-width: 1279px) {
  .login-brand {
    display: none;
  }
}
</style>
