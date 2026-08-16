<template>
  <div v-if="visible" class="agent-quick-bar">
    <span class="agent-quick-bar__label">常用</span>
    <button
      v-for="item in actions"
      :key="item.cmd || item.action"
      type="button"
      class="agent-quick-bar__chip"
      :class="{ 'agent-quick-bar__chip--alert': item.action === 'patrol' }"
      :disabled="loading"
      @click="onChip(item)"
    >
      {{ item.label }}
    </button>
  </div>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  loading: { type: Boolean, default: false },
  show: { type: Boolean, default: true },
  patrolPending: { type: Boolean, default: false }
})

const emit = defineEmits(['select', 'patrol'])

/** 五条黄金演示路径：巡检 / 磁盘 / 负载 / 清理预览 / 确认或续办 */
const baseActions = [
  { label: '一键巡检', cmd: '一键巡检本机健康状态' },
  { label: '查磁盘', cmd: '检查磁盘使用情况并扫描占用热点' },
  { label: '查负载进程', cmd: '查看系统负载和占用最高的进程' },
  { label: '清理预览', cmd: '预览清理 7 天前的临时文件' },
  { label: '确认执行', cmd: '确认执行' }
]

const actions = computed(() => {
  if (!props.patrolPending) {
    return baseActions
  }
  return [
    { label: '处理巡检待办', action: 'patrol' },
    ...baseActions.filter(a => a.label !== '确认执行')
  ]
})

const visible = computed(() => props.show)

function onChip (item) {
  if (item.action === 'patrol') {
    emit('patrol')
    return
  }
  emit('select', item.cmd)
}
</script>

<style scoped>
.agent-quick-bar {
  display: flex;
  align-items: center;
  gap: 8px;
  flex-wrap: wrap;
  padding: 8px 0 4px;
}

.agent-quick-bar__label {
  font-size: 12px;
  color: var(--agent-muted, #6b7280);
  font-weight: 500;
  flex-shrink: 0;
}

.agent-quick-bar__chip {
  padding: 6px 12px;
  border-radius: 999px;
  border: 1px solid rgba(13, 148, 136, 0.25);
  background: rgba(240, 253, 250, 0.9);
  color: #0f766e;
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  transition: background 0.15s, border-color 0.15s;
}

.agent-quick-bar__chip:hover:not(:disabled) {
  background: #ccfbf1;
  border-color: rgba(13, 148, 136, 0.45);
}

.agent-quick-bar__chip--alert {
  border-color: rgba(245, 158, 11, 0.4);
  background: rgba(254, 243, 199, 0.65);
  color: #b45309;
}

.agent-quick-bar__chip:disabled {
  opacity: 0.55;
  cursor: not-allowed;
}
</style>
