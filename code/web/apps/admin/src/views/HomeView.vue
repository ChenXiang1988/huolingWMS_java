<script setup lang="ts">
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
</script>

<template>
  <el-card>
    <template #header>工作台</template>
    <el-descriptions :column="2" border>
      <el-descriptions-item label="账号">{{ auth.profile?.accountName }}</el-descriptions-item>
      <el-descriptions-item label="角色">
        {{ auth.profile?.roles.join('、') }}
      </el-descriptions-item>
      <el-descriptions-item label="权限点数量">
        {{ auth.profile?.permissions.length ?? 0 }}
      </el-descriptions-item>
      <el-descriptions-item label="货主数据域">
        {{ auth.profile?.dataScope.allOwners ? '全部货主' : auth.profile?.dataScope.ownerIds.join('、') }}
      </el-descriptions-item>
      <el-descriptions-item label="仓数据域">
        {{ auth.profile?.dataScope.allWarehouses ? '全部仓' : auth.profile?.dataScope.warehouseIds.join('、') }}
      </el-descriptions-item>
    </el-descriptions>
  </el-card>
</template>
