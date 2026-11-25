<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ActionButton, FormInput } from '@wms/design'
import { useAuthStore } from '../stores/auth'
import { ApiError } from '@wms/request'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ accountName: '', password: '' })
const loading = ref(false)
const error = ref('')

async function submit() {
  error.value = ''
  loading.value = true
  try {
    await auth.login(form.accountName, form.password)
    const redirect = (route.query.redirect as string) || '/home'
    router.push(redirect)
  } catch (e) {
    error.value = e instanceof ApiError ? e.message : '登录失败，请稍后重试'
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <div class="login-page">
    <el-card class="login-card">
      <h2 class="login-title">货灵 WMS · 运营后台</h2>
      <el-form label-position="top" @submit.prevent="submit">
        <el-form-item label="账号">
          <FormInput v-model="form.accountName" autocomplete="username" />
        </el-form-item>
        <el-form-item label="密码">
          <FormInput v-model="form.password" type="password" autocomplete="current-password" />
        </el-form-item>
        <el-alert v-if="error" :title="error" type="error" :closable="false" show-icon />
        <ActionButton variant="primary" block class="login-submit" :loading="loading" @click="submit">
          登录
        </ActionButton>
      </el-form>
    </el-card>
  </div>
</template>

<style scoped>
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--el-bg-color-page);
}
.login-card {
  width: 360px;
  padding: 8px;
}
.login-title {
  margin: 0 0 24px;
  font-size: 20px;
  font-weight: 600;
  text-align: center;
}
.login-submit {
  width: 100%;
  margin-top: 16px;
}
</style>
