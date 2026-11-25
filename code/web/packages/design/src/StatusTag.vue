<script setup lang="ts">
defineProps<{
  label: string
  type?: 'success' | 'info' | 'warning' | 'danger'
}>()
</script>

<template>
  <el-tag size="small" :type="type ?? 'info'">{{ label }}</el-tag>
</template>
