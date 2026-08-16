<template>
  <div v-if="sessions.length > 0" class="session-list">
    <div v-if="!collapsed" class="session-list__label">最近对话</div>
    <div class="session-list__items">
      <button
        v-for="item in visibleSessions"
        :key="item.id"
        type="button"
        class="session-item"
        :class="{ active: item.id === activeSessionId }"
        :title="item.title"
        @click="$emit('select', item.id)"
      >
        <el-icon class="session-item__icon"><ChatDotRound /></el-icon>
        <span v-if="!collapsed" class="session-item__title">{{ item.title }}</span>
        <el-button
          v-if="!collapsed && sessions.length > 1"
          class="session-item__delete"
          text
          circle
          size="small"
          :icon="Delete"
          title="删除"
          @click.stop="$emit('delete', item.id)"
        />
      </button>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { ChatDotRound, Delete } from '@element-plus/icons-vue'

const props = defineProps({
  sessions: { type: Array, default: () => [] },
  activeSessionId: { type: String, default: '' },
  collapsed: { type: Boolean, default: false },
  limit: { type: Number, default: 8 }
})

defineEmits(['select', 'delete'])

const visibleSessions = computed(() =>
  [...props.sessions]
    .sort((a, b) => (b.updatedAt || 0) - (a.updatedAt || 0))
    .slice(0, props.limit)
)
</script>

<style scoped>
.session-list {
  padding: 4px 10px 8px;
  border-bottom: 1px solid var(--shell-border);
}

.session-list__label {
  padding: 6px 10px 4px;
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--shell-muted);
}

.session-list__items {
  display: flex;
  flex-direction: column;
  gap: 2px;
  max-height: 200px;
  overflow-y: auto;
}

.session-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  padding: 8px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--shell-text);
  font-size: 12px;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease;
}

.session-item:hover {
  background: var(--shell-nav-hover);
}

.session-item.active {
  background: var(--shell-nav-active);
  color: var(--ops-primary);
}

.session-item__icon {
  flex-shrink: 0;
  font-size: 14px;
  opacity: 0.7;
}

.session-item__title {
  flex: 1;
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.session-item__delete {
  flex-shrink: 0;
  opacity: 0;
  transition: opacity 0.15s ease;
}

.session-item:hover .session-item__delete {
  opacity: 0.65;
}

.collapsed .session-list__items {
  max-height: none;
}

.collapsed .session-item {
  justify-content: center;
  padding: 10px;
}
</style>
