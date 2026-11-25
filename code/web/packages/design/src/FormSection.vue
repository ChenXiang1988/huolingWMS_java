<script setup lang="ts">
defineProps<{
  title: string
}>()
</script>

<template>
  <section class="form-section">
    <h4 class="form-section-title">{{ title }}</h4>
    <slot />
  </section>
</template>

<style scoped>
.form-section {
  margin-bottom: 24px;
}
.form-section-title {
  margin: 0 0 16px;
  font-size: 14px;
  font-weight: 600;
  color: var(--el-text-color-primary);
}
</style>
