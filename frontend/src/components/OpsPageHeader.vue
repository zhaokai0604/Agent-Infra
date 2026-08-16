<template>
  <header class="ops-page-header">
    <div class="ops-page-header__main">
      <h1>{{ title }}</h1>
      <p v-if="subtitle" class="ops-page-header__subtitle">{{ subtitle }}</p>
    </div>
    <div v-if="$slots.actions" class="ops-page-header__actions">
      <slot name="actions" />
    </div>
  </header>
</template>

<script setup>
defineProps({
  title: { type: String, required: true },
  subtitle: { type: String, default: '' }
})
</script>

<style scoped>
.ops-page-header {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 20px;
  padding: 4px 0 16px;
  border-bottom: 1px solid var(--ops-border-soft);
}

.ops-page-header__main h1 {
  margin: 0 0 6px;
  font-size: 22px;
  font-weight: 650;
  letter-spacing: -0.02em;
  color: var(--ops-text);
  line-height: 1.25;
}

.ops-page-header__subtitle {
  margin: 0;
  font-size: 14px;
  line-height: 1.55;
  color: var(--ops-text-muted);
  max-width: 720px;
}

.ops-page-header__actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  flex-shrink: 0;
}
</style>
