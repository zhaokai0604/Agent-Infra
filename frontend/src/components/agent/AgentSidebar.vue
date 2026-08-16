<template>
  <aside class="agent-sidebar" :class="{ collapsed }">
    <div class="sidebar-brand">
      <div class="brand-logo-wrap">
        <img src="/threshcore-logo.png" alt="" class="brand-logo">
      </div>
      <div v-if="!collapsed" class="brand-copy">
        <strong>ThreshCore</strong>
        <span>运维 Agent</span>
      </div>
    </div>

    <div class="sidebar-actions">
      <button type="button" class="new-chat-btn" @click="$emit('new-chat')">
        <el-icon><Plus /></el-icon>
        <span v-if="!collapsed">新建会话</span>
      </button>
    </div>

    <nav class="sidebar-nav">
      <button
        type="button"
        class="nav-item"
        :class="{ active: activeTab === 'ops-chat' }"
        @click="$emit('select', 'ops-chat')"
      >
        <el-icon><ChatLineSquare /></el-icon>
        <span v-if="!collapsed">运维对话</span>
      </button>
      <button
        type="button"
        class="nav-item"
        :class="{ active: activeTab === 'agent-skills' }"
        @click="$emit('select', 'agent-skills')"
      >
        <el-icon><Grid /></el-icon>
        <span v-if="!collapsed">工具与技能</span>
      </button>
    </nav>

    <AgentSessionList
      v-if="showSessions"
      :sessions="sessions"
      :active-session-id="activeSessionId"
      :collapsed="collapsed"
      @select="$emit('select-session', $event)"
      @delete="$emit('delete-session', $event)"
    />

    <div v-if="!collapsed" class="sidebar-section">
      <button type="button" class="section-toggle" @click="opsExpanded = !opsExpanded">
        <span>运行任务</span>
        <el-icon :class="{ rotated: opsExpanded }"><ArrowDown /></el-icon>
      </button>

      <div v-show="opsExpanded" class="ops-nav-list">
        <template v-for="group in opsNavGroups" :key="group.title">
          <div class="ops-group-label">{{ group.title }}</div>
          <button
            v-for="item in group.items"
            :key="item.key"
            type="button"
            class="nav-item nav-item--sub"
            :class="{ active: activeTab === item.key }"
            @click="$emit('select', item.key)"
          >
            <el-icon><component :is="item.icon" /></el-icon>
            <span>{{ item.label }}</span>
          </button>
        </template>
      </div>
    </div>

    <div v-else class="sidebar-collapsed-ops">
      <el-tooltip v-for="item in flatNavItems" :key="item.key" :content="item.label" placement="right">
        <button
          type="button"
          class="nav-item nav-item--icon"
          :class="{ active: activeTab === item.key }"
          @click="$emit('select', item.key)"
        >
          <el-icon><component :is="item.icon" /></el-icon>
        </button>
      </el-tooltip>
    </div>

    <div class="sidebar-footer">
      <el-button text circle size="small" :icon="collapsed ? Expand : Fold" @click="$emit('toggle-collapse')" />
    </div>
  </aside>
</template>

<script setup>
import { computed, ref } from 'vue'
import {
  ArrowDown,
  ChatLineSquare,
  Expand,
  Fold,
  Grid,
  Plus
} from '@element-plus/icons-vue'

import AgentSessionList from './AgentSessionList.vue'

const props = defineProps({
  activeTab: { type: String, default: 'ops-chat' },
  collapsed: { type: Boolean, default: false },
  navGroups: { type: Array, default: () => [] },
  sessions: { type: Array, default: () => [] },
  activeSessionId: { type: String, default: '' },
  showSessions: { type: Boolean, default: true }
})

defineEmits(['select', 'new-chat', 'toggle-collapse', 'select-session', 'delete-session'])

const opsExpanded = ref(true)

const opsNavGroups = computed(() =>
  props.navGroups
    .map(group => ({
      ...group,
      items: (group.items || []).filter(item => !['ops-chat', 'agent-skills'].includes(item.key))
    }))
    .filter(group => group.items.length > 0)
)

const flatNavItems = computed(() =>
  opsNavGroups.value.flatMap(group => group.items || [])
)
</script>

<style scoped>
.agent-sidebar {
  position: fixed;
  inset: 0 auto 0 0;
  width: var(--agent-sidebar-w);
  background: var(--shell-sidebar);
  border-right: 1px solid var(--shell-border);
  display: flex;
  flex-direction: column;
  z-index: 1000;
  transition: width 0.25s ease;
  color: var(--shell-text);
}

.agent-sidebar.collapsed {
  width: var(--agent-sidebar-collapsed-w);
}

.sidebar-brand {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 16px 14px;
  border-bottom: 1px solid var(--shell-border);
}

.brand-logo-wrap {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  overflow: hidden;
  flex-shrink: 0;
}

.brand-logo {
  width: 40px;
  display: block;
}

.brand-copy {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.brand-copy strong {
  font-size: 15px;
  font-weight: 600;
}

.brand-copy span {
  font-size: 11px;
  color: var(--shell-muted);
}

.sidebar-actions {
  padding: 12px 14px 8px;
}

.new-chat-btn {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  padding: 10px 14px;
  border: 1px solid var(--shell-border);
  border-radius: 10px;
  background: var(--shell-accent-soft);
  color: var(--shell-text);
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  transition: background 0.15s ease;
}

.new-chat-btn:hover {
  background: var(--shell-nav-hover);
}

.collapsed .new-chat-btn span {
  display: none;
}

.sidebar-nav {
  padding: 4px 10px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-section {
  flex: 1;
  overflow-y: auto;
  padding: 8px 10px 12px;
  min-height: 0;
}

.section-toggle {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  padding: 8px 10px;
  border: none;
  background: none;
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.06em;
  text-transform: uppercase;
  color: var(--shell-muted);
  cursor: pointer;
}

.section-toggle .el-icon {
  transition: transform 0.2s ease;
}

.section-toggle .rotated {
  transform: rotate(-90deg);
}

.ops-group-label {
  padding: 10px 10px 4px;
  font-size: 10px;
  font-weight: 600;
  color: var(--shell-muted);
  text-transform: uppercase;
  letter-spacing: 0.06em;
}

.nav-item {
  display: flex;
  align-items: center;
  gap: 8px;
  width: 100%;
  min-width: 0;
  padding: 9px 10px;
  border: none;
  border-radius: 8px;
  background: transparent;
  color: var(--shell-text);
  font-size: 12px;
  font-weight: 500;
  cursor: pointer;
  text-align: left;
  transition: background 0.15s ease, color 0.15s ease;
}

.nav-item:hover {
  background: var(--shell-nav-hover);
}

.nav-item.active {
  background: var(--shell-nav-active);
  color: var(--ops-primary);
}

.nav-item--sub {
  padding-left: 14px;
}

.nav-item--icon {
  justify-content: center;
  padding: 10px;
}

.ops-nav-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.sidebar-collapsed-ops {
  display: flex;
  flex-direction: column;
  gap: 2px;
  padding: 4px 10px 12px;
}

.sidebar-footer {
  padding: 10px;
  border-top: 1px solid var(--shell-border);
  display: flex;
  justify-content: center;
}

:deep(.el-button.is-text.el-button--small) {
  color: var(--shell-muted);
}

:deep(.el-button.is-text.el-button--small:hover) {
  color: var(--ops-primary);
}
</style>
