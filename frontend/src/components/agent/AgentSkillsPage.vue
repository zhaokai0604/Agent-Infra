<template>
  <div class="agent-skills-page">
    <div class="skills-header agent-chat-column">
      <h1>运维技能</h1>
      <p>本机已注册的运维工具能力。在线技能可在对话中自然语言触发，或点击「在对话中使用」。</p>
    </div>

    <el-alert
      v-if="!registryConnected"
      type="warning"
      :closable="false"
      show-icon
      class="conn-alert agent-chat-column"
      title="未连接运维服务"
      description="请确认后端已启动并已登录。"
    />

    <div v-if="registryConnected" class="ops-stat-row agent-chat-column">
      <div class="ops-stat-pill">
        <span class="ops-stat-pill__label">已注册</span>
        <span class="ops-stat-pill__value">{{ tools.length }}</span>
      </div>
      <div class="ops-stat-pill ops-stat-pill--ok">
        <span class="ops-stat-pill__label">在线</span>
        <span class="ops-stat-pill__value">{{ onlineCount }}</span>
      </div>
      <div class="ops-stat-pill">
        <span class="ops-stat-pill__label">离线</span>
        <span class="ops-stat-pill__value">{{ offlineCount }}</span>
      </div>
    </div>

    <div class="skills-toolbar agent-chat-column">
      <el-input
        v-model="searchQuery"
        clearable
        placeholder="搜索技能名称或描述…"
        :prefix-icon="Search"
        class="skills-search"
      />
      <el-radio-group v-model="statusFilter" size="small">
        <el-radio-button value="all">全部 {{ tools.length }}</el-radio-button>
        <el-radio-button value="online">在线 {{ onlineCount }}</el-radio-button>
        <el-radio-button value="offline">离线 {{ offlineCount }}</el-radio-button>
      </el-radio-group>
    </div>

    <div v-loading="loading" class="skills-grid agent-chat-column">
      <article
        v-for="tool in filteredTools"
        :key="tool.name"
        class="skill-card"
        :class="{ offline: tool.status !== 'online' }"
      >
        <div class="skill-badge">{{ mcpToolBadge(tool.name) }}</div>
        <h3>{{ mcpToolDisplayName(tool.name) }}</h3>
        <p class="skill-cmd">{{ getMcpToolCommandHint(tool.name) }}</p>
        <p class="skill-desc">{{ tool.description }}</p>
        <div class="skill-meta">
          <span class="status-dot" :class="tool.status" />
          {{ tool.statusText }}
        </div>
        <div class="skill-actions">
          <el-button
            size="small"
            type="primary"
            :disabled="tool.status !== 'online' || executing === tool.name"
            :loading="executing === tool.name"
            @click="executeNow(tool)"
          >
            立即执行
          </el-button>
          <el-button
            size="small"
            type="primary"
            plain
            :disabled="tool.status !== 'online'"
            @click="useInChat(tool)"
          >
            在对话中使用
          </el-button>
        </div>
      </article>
      <el-empty v-if="!loading && filteredTools.length === 0" description="没有匹配的技能" class="skills-empty" />
    </div>

    <ToolResultDialog ref="resultDialogRef" />
  </div>
</template>

<script setup>
import { computed, onMounted, ref } from 'vue'
import { Search } from '@element-plus/icons-vue'
import { getMcpTools, getPlatformInfo } from '../../api'
import { mergeToolRegistry, mcpToolDisplayName, mcpToolBadge, getMcpToolCommandHint, defaultParamsForTool, coerceToolParams } from '../../utils/mcpToolsMeta'
import { runMcpToolExecute } from '../../utils/mcpToolExecute'
import ToolResultDialog from '../ToolResultDialog.vue'

const tools = ref([])
const loading = ref(false)
const registryConnected = ref(true)
const searchQuery = ref('')
const statusFilter = ref('all')
const executing = ref(null)
const resultDialogRef = ref(null)

const filteredTools = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return tools.value.filter(tool => {
    if (statusFilter.value === 'online' && tool.status !== 'online') return false
    if (statusFilter.value === 'offline' && tool.status === 'online') return false
    if (!q) return true
    const hay = `${tool.name} ${tool.description || ''} ${mcpToolDisplayName(tool.name)}`.toLowerCase()
    return hay.includes(q)
  })
})

const onlineCount = computed(() => tools.value.filter(t => t.status === 'online').length)
const offlineCount = computed(() => tools.value.filter(t => t.status !== 'online').length)

async function loadTools() {
  loading.value = true
  try {
    const [raw, platform] = await Promise.all([
      getMcpTools(),
      getPlatformInfo().catch(() => null)
    ])
    tools.value = mergeToolRegistry(raw, { platformInfo: platform })
    registryConnected.value = true
  } catch {
    registryConnected.value = false
    tools.value = mergeToolRegistry([], { platformInfo: null })
  } finally {
    loading.value = false
  }
}

async function executeNow(tool) {
  if (!tool?.name || tool.status !== 'online') return
  executing.value = tool.name
  try {
    const params = coerceToolParams(defaultParamsForTool(tool), tool)
    const data = await runMcpToolExecute(tool.name, params)
    if (!data) return
    resultDialogRef.value?.open({
      toolName: tool.name,
      success: data.success,
      duration: data.duration || 0,
      traceId: data.traceId,
      writeMismatch: data.writeMismatch === true,
      mode: typeof data?.data?.mode === 'string' ? data.data.mode : data.mode,
      raw: data
    })
  } finally {
    executing.value = null
  }
}

function useInChat(tool) {
  const hint = getMcpToolCommandHint(tool.name)
  const message = hint && hint !== 'system command'
    ? `请使用 ${tool.name}：${hint}`
    : `请执行 ${mcpToolDisplayName(tool.name)}`
  window.dispatchEvent(new CustomEvent('ops-navigate-agent', { detail: { message } }))
}

onMounted(loadTools)
</script>

<style scoped>
.agent-skills-page {
  flex: 1;
  min-height: calc(100vh - var(--ops-header-h, 52px));
  overflow-y: auto;
  padding: 24px 0 40px;
  background: var(--agent-surface);
}

.skills-header {
  margin-bottom: 24px;
}

.skills-header h1 {
  margin: 0 0 8px;
  font-size: 22px;
  font-weight: 600;
  color: var(--ops-text);
}

.skills-header p {
  margin: 0;
  font-size: 14px;
  color: var(--agent-muted);
  line-height: 1.5;
}

.conn-alert {
  margin-bottom: 20px;
}

.skills-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  margin-bottom: 18px;
}

.skills-search {
  flex: 1;
  min-width: 200px;
  max-width: 420px;
}

.skills-empty {
  grid-column: 1 / -1;
  padding: 24px 0;
}

.skills-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(220px, 1fr));
  gap: 14px;
}

.skill-card {
  display: flex;
  flex-direction: column;
  padding: 16px;
  border: 1px solid var(--agent-border);
  border-radius: 12px;
  background: var(--agent-surface);
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
}

.skill-card:hover {
  border-color: rgba(13, 148, 136, 0.35);
  box-shadow: 0 6px 20px rgba(15, 23, 42, 0.07);
  transform: translateY(-1px);
}

.skill-card.offline {
  opacity: 0.65;
}

.skill-badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 36px;
  height: 36px;
  border-radius: 8px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  font-size: 11px;
  font-weight: 700;
  margin-bottom: 10px;
}

.skill-card h3 {
  margin: 0 0 6px;
  font-size: 14px;
  font-weight: 600;
  color: var(--ops-text);
}

.skill-cmd {
  margin: 0 0 6px;
  font-family: var(--ops-font-mono);
  font-size: 11px;
  color: var(--ops-primary);
  opacity: 0.85;
}

.skill-desc {
  margin: 0 0 12px;
  font-size: 12px;
  line-height: 1.45;
  color: var(--agent-muted);
  flex: 1;
}

.skill-meta {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 11px;
  color: var(--agent-muted);
  margin-bottom: 12px;
}

.status-dot {
  width: 6px;
  height: 6px;
  border-radius: 50%;
  background: #22c55e;
}

.status-dot.offline {
  background: #a1a1aa;
}

.skill-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: auto;
  padding-top: 8px;
}
</style>
