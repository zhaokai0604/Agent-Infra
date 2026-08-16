<template>
  <aside class="ops-context-panel" :class="{ 'ops-context-panel--embedded': embedded }">
    <div v-if="!embedded" class="ops-context-panel__header">本机负载</div>
    <div class="ops-context-panel__body">
      <TopPerformanceBar layout="vertical" lite />
      <div class="ops-context-panel__alerts">
        <div class="ops-context-panel__alerts-title">驾驶舱提醒</div>
        <OpsSideAlerts />
      </div>
    </div>
  </aside>
</template>

<script setup>
import TopPerformanceBar from '../TopPerformanceBar.vue'
import OpsSideAlerts from './OpsSideAlerts.vue'

defineProps({
  embedded: { type: Boolean, default: false }
})
</script>

<style scoped>
.ops-context-panel--embedded {
  width: 100%;
  border: none;
  background: transparent;
}

.ops-context-panel--embedded .ops-context-panel__body {
  padding: 0;
}

.ops-context-panel__alerts {
  margin-top: 12px;
  padding-top: 12px;
  border-top: 1px solid rgba(148, 163, 184, 0.2);
}

.ops-context-panel__alerts-title {
  margin-bottom: 8px;
  color: #64748b;
  font-size: 12px;
  font-weight: 600;
}
</style>
