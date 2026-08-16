<template>
  <div v-if="plan" class="remediation-plan-card">
    <div class="plan-head">
      <el-icon><List /></el-icon>
      <span class="plan-title">{{ plan.title }}</span>
      <el-tag v-if="plan.previewOnly" size="small" type="warning">预览模式</el-tag>
      <el-tag v-if="observeOnly" size="small" type="info">本轮仅观测工具</el-tag>
    </div>
    <p class="plan-body">{{ planSummary }}</p>
    <p v-if="pendingWriteLabel" class="plan-pending">待挂载写工具：{{ pendingWriteLabel }}</p>
    <div v-if="showConfirm" class="plan-actions">
      <el-button type="primary" size="small" :loading="loading" @click="$emit('confirm')">
        确认执行
      </el-button>
      <span class="plan-hint">确认后挂载写工具，并在路径白名单与策略允许范围内落地</span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { List } from '@element-plus/icons-vue'

const props = defineProps({
  plan: { type: Object, default: null },
  showConfirm: { type: Boolean, default: true },
  loading: { type: Boolean, default: false },
  observeOnly: { type: Boolean, default: false },
  pendingWriteTools: { type: Array, default: () => [] }
})

defineEmits(['confirm'])

const stepCount = computed(() => (Array.isArray(props.plan?.items) ? props.plan.items.length : 0))

const planSummary = computed(() => {
  if (props.plan?.summary) return props.plan.summary
  if (stepCount.value > 0) return `已生成 ${stepCount.value} 步处置方案，详细步骤已收敛到审计链路。`
  return '已生成处置方案，详细内容可在审计链路复核。'
})

const pendingWriteLabel = computed(() => {
  const tools = props.pendingWriteTools
  if (!Array.isArray(tools) || !tools.length) return ''
  return tools.join('、')
})
</script>

<style scoped>
.remediation-plan-card {
  margin-top: 10px;
  padding: 12px 14px;
  border: 1px solid var(--el-color-warning-light-5);
  border-radius: 8px;
  background: var(--el-color-warning-light-9);
}

.plan-head {
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
  font-weight: 600;
  font-size: 14px;
  flex-wrap: wrap;
}

.plan-body {
  margin: 0 0 10px;
  font-size: 13px;
  line-height: 1.55;
  white-space: pre-wrap;
}

.plan-pending {
  margin: 0 0 10px;
  font-size: 12px;
  color: var(--el-color-warning-dark-2, #b88230);
}

.plan-actions {
  display: flex;
  align-items: center;
  gap: 10px;
  flex-wrap: wrap;
}

.plan-hint {
  font-size: 12px;
  color: var(--ops-text-muted, #888);
}
</style>
