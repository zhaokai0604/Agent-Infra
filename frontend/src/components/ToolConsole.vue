<template>
  <div class="ops-page tool-console">
    <OpsPageHeader
      title="工具箱"
      subtitle="连接本机运维服务执行能力；写操作经确认后才会落地"
    >
      <template #actions>
        <el-tag v-if="registryConnected" type="success" effect="plain" round>
          {{ onlineCount }}/{{ tools.length }} 在线
        </el-tag>
        <el-button size="small" :loading="toolsLoading" @click="refreshAll">刷新</el-button>
      </template>
    </OpsPageHeader>

    <el-alert
      v-if="!registryConnected"
      type="warning"
      :closable="false"
      show-icon
      class="conn-alert"
      title="未连接运维服务"
      description="请确认后端已启动并已登录。工具状态与执行记录均来自真实环境。"
    />

    <div v-if="registryConnected" class="ops-stat-row">
      <div class="ops-stat-pill">
        <span class="ops-stat-pill__label">已注册</span>
        <span class="ops-stat-pill__value">{{ tools.length }}</span>
      </div>
      <div class="ops-stat-pill ops-stat-pill--ok">
        <span class="ops-stat-pill__label">可执行</span>
        <span class="ops-stat-pill__value">{{ onlineCount }}</span>
      </div>
      <div class="ops-stat-pill">
        <span class="ops-stat-pill__label">最近执行</span>
        <span class="ops-stat-pill__value">{{ executionHistory.length }}</span>
      </div>
    </div>

    <el-tabs v-model="activeTab" type="border-card" class="tools-tabs ops-tabs">
      <el-tab-pane label="能力列表" name="overview">
        <div class="overview-toolbar">
          <el-input
            v-model="searchQuery"
            clearable
            placeholder="搜索工具名称或描述…"
            :prefix-icon="Search"
            class="overview-search"
          />
          <el-radio-group v-model="statusFilter" size="small">
            <el-radio-button value="all">全部</el-radio-button>
            <el-radio-button value="online">在线</el-radio-button>
          </el-radio-group>
        </div>
        <div class="tools-grid">
          <el-card
            v-for="tool in filteredTools"
            :key="tool.name"
            class="tool-card"
            :class="{ active: selectedTool === tool.name, offline: tool.status !== 'online' }"
            shadow="never"
            @click="selectTool(tool)"
          >
            <div class="tool-card__head">
              <div class="tool-icon-badge">{{ mcpToolBadge(tool.name) }}</div>
              <el-button
                v-if="tool.status === 'online'"
                class="tool-card__run"
                type="primary"
                link
                size="small"
                :loading="executing === tool.name"
                @click.stop="quickRun(tool)"
              >
                快速执行
              </el-button>
            </div>
            <div class="tool-name">{{ mcpToolDisplayName(tool.name) }}</div>
            <div class="tool-desc">{{ tool.description }}</div>
            <div class="tool-cmd" v-if="getMcpToolCommandHint(tool.name)">
              {{ getMcpToolCommandHint(tool.name) }}
            </div>
            <div class="tool-status" :class="tool.status">
              <span class="status-dot" :class="tool.status" />
              {{ tool.statusText }}
            </div>
            <div v-if="tool.status !== 'online' && tool.platformSupport?.reason" class="tool-status-reason">
              {{ tool.platformSupport.reason }}
            </div>
          </el-card>
          <el-empty v-if="!filteredTools.length" description="没有匹配的工具" class="tools-empty" />
        </div>
      </el-tab-pane>

      <el-tab-pane label="立即执行" name="execute">
        <div class="execute-panel">
          <div v-if="!selectedTool" class="no-selection">
            <el-empty description="请先在「能力列表」中选择一项，或点击卡片上的「快速执行」" />
          </div>
          <template v-else>
            <div class="execute-layout">
              <section class="execute-main">
                <div class="selected-tool-info">
                  <div class="selected-tool-info__badge">{{ mcpToolBadge(selectedTool) }}</div>
                  <div>
                    <h3>{{ selectedToolLabel }}</h3>
                    <p>{{ getToolByName(selectedTool)?.description }}</p>
                  </div>
                </div>
                <div v-if="getToolByName(selectedTool)?.params?.length" class="tool-params">
                  <h4>参数</h4>
                  <el-form :model="params" label-width="108px" label-position="left">
                    <el-form-item
                      v-for="p in getToolByName(selectedTool)?.params"
                      :key="p.name"
                      :label="p.label"
                    >
                      <el-select
                        v-if="p.type === 'select'"
                        v-model="params[p.name]"
                        :placeholder="p.placeholder"
                        style="width: 100%"
                      >
                        <el-option v-for="opt in p.options" :key="opt" :label="opt" :value="opt" />
                      </el-select>
                      <el-switch
                        v-else-if="p.type === 'boolean'"
                        v-model="params[p.name]"
                        active-value="true"
                        inactive-value="false"
                      />
                      <el-input
                        v-else
                        v-model="params[p.name]"
                        :placeholder="p.placeholder"
                        :type="p.type === 'number' ? 'number' : 'text'"
                      />
                      <span class="param-hint">{{ p.hint }}</span>
                    </el-form-item>
                  </el-form>
                </div>
                <div v-else class="no-params-hint">此工具无需参数，可直接执行。</div>
                <div class="execute-actions">
                  <el-button type="primary" size="large" :loading="executing === true" :icon="Cpu" @click="executeTool">
                    执行
                  </el-button>
                  <el-button size="large" @click="resetParams">恢复默认</el-button>
                </div>
              </section>
              <aside class="execute-aside">
                <div class="aside-block">
                  <div class="aside-label">命令提示</div>
                  <code class="aside-code">{{ commandHint }}</code>
                </div>
                <div class="aside-block">
                  <div class="aside-label">Bean 名称</div>
                  <code class="aside-code">{{ selectedTool }}</code>
                </div>
                <div class="aside-block" v-if="getToolByName(selectedTool)?.defaultRiskScore != null">
                  <div class="aside-label">默认风险分</div>
                  <code class="aside-code">{{ getToolByName(selectedTool)?.defaultRiskScore }}</code>
                </div>
                <el-alert type="info" :closable="false" show-icon title="写操作说明">
                  清理、重启、杀进程等默认仅预览；确认后才会真正执行。
                </el-alert>
                <el-alert
                  v-if="getToolByName(selectedTool)?.platformSupport?.available === false"
                  type="warning"
                  :closable="false"
                  show-icon
                  :title="getToolByName(selectedTool)?.platformSupport?.reason || '当前平台不可用'"
                />
              </aside>
            </div>
          </template>
        </div>
      </el-tab-pane>

      <el-tab-pane label="执行记录" name="history">
        <div class="history-toolbar">
          <span class="history-hint">来自审计库的真实工具执行记录</span>
          <el-button size="small" :loading="historyLoading" @click="loadExecutionHistory">刷新</el-button>
        </div>
        <el-table :data="executionHistory" stripe style="width: 100%" v-loading="historyLoading" empty-text="暂无执行记录">
          <el-table-column prop="toolName" label="工具" width="160">
            <template #default="scope">{{ mcpToolDisplayName(scope.row.toolName) }}</template>
          </el-table-column>
          <el-table-column prop="timestamp" label="执行时间" width="180">
            <template #default="scope">{{ formatExecTime(scope.row.timestamp) }}</template>
          </el-table-column>
          <el-table-column prop="duration" label="耗时" width="100">
            <template #default="scope">
              <el-tag size="small">{{ scope.row.duration }}ms</el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="status" label="状态" width="88">
            <template #default="scope">
              <el-tag size="small" :type="scope.row.success ? 'success' : 'danger'">
                {{ scope.row.success ? '成功' : '失败' }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="resultPreview" label="结果预览" show-overflow-tooltip />
          <el-table-column label="操作" width="180" fixed="right">
            <template #default="scope">
              <el-button type="primary" link @click="viewDetail(scope.row)">详情</el-button>
              <el-button v-if="scope.row.traceId" type="success" link @click="openTrace(scope.row)">溯源</el-button>
            </template>
          </el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <ToolResultDialog ref="resultDialogRef" />
  </div>
</template>

<script setup>
import { ref, reactive, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Cpu, Search } from '@element-plus/icons-vue'
import { getAuditFeed, getMcpTools, getPlatformInfo } from '../api'
import { formatMcpResultPreview } from '../utils/mcpHumanReadable'
import {
  mergeToolRegistry,
  getMcpToolCommandHint,
  mcpToolBadge,
  mcpToolDisplayName,
  defaultParamsForTool,
  coerceToolParams
} from '../utils/mcpToolsMeta'
import { runMcpToolExecute } from '../utils/mcpToolExecute'
import OpsPageHeader from './OpsPageHeader.vue'
import ToolResultDialog from './ToolResultDialog.vue'

const activeTab = ref('overview')
const selectedTool = ref(null)
const executing = ref(false)
const toolsLoading = ref(false)
const resultDialogRef = ref(null)
const executionHistory = ref([])
const registryConnected = ref(false)
const historyLoading = ref(false)
const searchQuery = ref('')
const statusFilter = ref('all')

const params = reactive({})
const tools = ref([])

const selectedToolLabel = computed(() =>
  selectedTool.value ? mcpToolDisplayName(selectedTool.value) : ''
)

const onlineCount = computed(() => tools.value.filter((t) => t.status === 'online').length)

const commandHint = computed(() =>
  selectedTool.value ? getMcpToolCommandHint(selectedTool.value) : '—'
)

const filteredTools = computed(() => {
  const q = searchQuery.value.trim().toLowerCase()
  return tools.value.filter((tool) => {
    if (tool.name.startsWith('Remote')) return false
    if (statusFilter.value === 'online' && tool.status !== 'online') return false
    if (!q) return true
    const hay = `${tool.name} ${tool.description || ''} ${mcpToolDisplayName(tool.name)}`.toLowerCase()
    return hay.includes(q)
  })
})

const loadTools = async () => {
  toolsLoading.value = true
  try {
    const [raw, platform] = await Promise.all([
      getMcpTools(),
      getPlatformInfo().catch(() => null)
    ])
    tools.value = mergeToolRegistry(raw, { registryLoaded: true, platformInfo: platform })
      .filter((t) => !t.name.startsWith('Remote'))
    registryConnected.value = true
  } catch {
    tools.value = mergeToolRegistry(null, { registryLoaded: false })
    registryConnected.value = false
    ElMessage.warning('未能连接运维服务，请确认已登录')
  } finally {
    toolsLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadTools(), loadExecutionHistory()])
}

function formatExecTime(ts) {
  if (!ts) return '—'
  const d = new Date(ts)
  return Number.isNaN(d.getTime()) ? String(ts) : d.toLocaleString('zh-CN')
}

function openTrace(row) {
  const tid = row?.traceId
  if (!tid) return
  window.dispatchEvent(
    new CustomEvent('ops-navigate-tab', { detail: { tab: 'audit', traceId: tid } })
  )
}

const loadExecutionHistory = async () => {
  historyLoading.value = true
  try {
    const rows = await getAuditFeed(80)
    executionHistory.value = (Array.isArray(rows) ? rows : [])
      .filter((r) => r?.toolName && r.toolName !== 'NONE')
      .map((r) => ({
        toolName: r.toolName,
        timestamp: r.createdAt || '',
        duration: r.durationMs || 0,
        success: r.executionOk === true,
        resultPreview: formatMcpResultPreview(r.toolName, { data: r.resultSummary || r.summary, success: r.executionOk }),
        traceId: r.traceId,
        fullResult: r
      }))
  } catch {
    ElMessage.error('加载执行记录失败')
  } finally {
    historyLoading.value = false
  }
}

onMounted(async () => {
  await refreshAll()
})

const getToolByName = (name) => tools.value.find((t) => t.name === name)

const fillDefaultParams = (tool) => {
  Object.keys(params).forEach((key) => delete params[key])
  if (tool) Object.assign(params, defaultParamsForTool(tool))
}

const selectTool = (tool) => {
  selectedTool.value = tool.name
  activeTab.value = 'execute'
  fillDefaultParams(tool)
}

const resetParams = () => {
  fillDefaultParams(getToolByName(selectedTool.value))
}

const presentResult = (result) => {
  const full = result.fullResult ?? result.raw
  resultDialogRef.value?.open({
    toolName: result.toolName,
    success: result.success,
    duration: result.duration,
    traceId: result.traceId || full?.traceId,
    writeMismatch: full?.writeMismatch === true || result.writeMismatch === true,
    mode: full?.data?.mode || full?.mode || result.mode,
    raw: full
  })
}

async function runTool(toolName, toolMeta) {
  if (!registryConnected.value) {
    ElMessage.error('未连接运维服务，无法执行')
    return null
  }
  if (toolMeta?.platformSupport?.available === false) {
    ElMessage.warning(toolMeta.platformSupport.reason || '所选工具当前平台不可用')
    return null
  }
  if (toolMeta?.status !== 'online') {
    ElMessage.warning(toolMeta?.statusText || '所选工具当前不可用')
    return null
  }
  const payload = coerceToolParams({ ...params }, toolMeta)
  return runMcpToolExecute(toolName, payload)
}

const executeTool = async () => {
  if (!selectedTool.value) return
  const toolMeta = getToolByName(selectedTool.value)
  executing.value = true
  try {
    const data = await runTool(selectedTool.value, toolMeta)
    if (!data) return
    const result = {
      toolName: selectedTool.value,
      timestamp: new Date().toLocaleString('zh-CN'),
      duration: data.duration || 0,
      success: data.success,
      resultPreview: formatMcpResultPreview(selectedTool.value, data),
      fullResult: data
    }
    executionHistory.value.unshift(result)
    presentResult(result)
    await loadExecutionHistory()
  } finally {
    executing.value = false
  }
}

async function quickRun(tool) {
  if (tool.status !== 'online') return
  selectedTool.value = tool.name
  fillDefaultParams(tool)
  executing.value = tool.name
  try {
    const data = await runTool(tool.name, tool)
    if (!data) return
    presentResult({
      toolName: tool.name,
      success: data.success,
      duration: data.duration || 0,
      traceId: data.traceId,
      fullResult: data
    })
    await loadExecutionHistory()
  } finally {
    executing.value = false
  }
}

const viewDetail = (row) => {
  presentResult(row)
}
</script>

<style scoped>
.tool-console {
  padding: 0;
  background: transparent;
  min-height: auto;
}

.conn-alert {
  margin-bottom: 14px;
}

.tools-tabs {
  background: var(--ops-panel);
  border-radius: var(--ops-radius);
  overflow: hidden;
  border: 1px solid var(--ops-border);
  box-shadow: var(--ops-shadow-sm);
}

.tools-tabs :deep(.el-tabs__header) {
  margin: 0;
  background: var(--ops-panel-soft);
  border-bottom: 1px solid var(--ops-border);
}

.tools-tabs :deep(.el-tabs__content) {
  padding: 0;
}

.overview-toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 12px;
  padding: 16px 20px 0;
}

.overview-search {
  flex: 1;
  min-width: 200px;
  max-width: 360px;
}

.tools-grid {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(260px, 1fr));
  gap: 14px;
  padding: 16px 20px 20px;
}

.tools-empty {
  grid-column: 1 / -1;
}

.tool-card {
  cursor: pointer;
  transition: border-color 0.15s ease, box-shadow 0.15s ease, transform 0.15s ease;
  border: 1px solid var(--ops-border) !important;
  background: var(--ops-panel) !important;
  border-radius: 12px !important;
}

.tool-card:hover {
  box-shadow: var(--ops-shadow-md);
  border-color: #94a3b8 !important;
  transform: translateY(-1px);
}

.tool-card.active {
  border-color: var(--ops-primary) !important;
  box-shadow: 0 0 0 1px rgba(13, 148, 136, 0.25);
}

.tool-card.offline {
  opacity: 0.68;
}

.tool-card__head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
}

.tool-card__run {
  flex-shrink: 0;
  font-weight: 600;
}

.tool-icon-badge {
  width: 40px;
  height: 40px;
  border-radius: 10px;
  background: rgba(13, 148, 136, 0.1);
  color: var(--ops-primary);
  font-size: 10px;
  font-weight: 700;
  letter-spacing: 0.04em;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--ops-font-mono);
}

.tool-name {
  font-size: 14px;
  font-weight: 600;
  color: var(--ops-text);
  margin-bottom: 4px;
}

.tool-desc {
  font-size: 12px;
  color: var(--ops-text-subtle);
  margin-bottom: 8px;
  line-height: 1.45;
  min-height: 34px;
}

.tool-cmd {
  font-family: var(--ops-font-mono);
  font-size: 11px;
  color: #64748b;
  background: var(--ops-panel-soft);
  padding: 4px 8px;
  border-radius: 6px;
  margin-bottom: 10px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.tool-status {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 12px;
  color: var(--ops-text-subtle);
}

.tool-status-reason {
  margin-top: 8px;
  font-size: 12px;
  color: #b45309;
  line-height: 1.5;
}

.status-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  background: #22c55e;
}

.status-dot.offline {
  background: #a1a1aa;
}

.execute-panel {
  padding: 20px;
}

.execute-layout {
  display: grid;
  grid-template-columns: minmax(0, 1fr) 280px;
  gap: 20px;
  align-items: start;
}

@media (max-width: 900px) {
  .execute-layout {
    grid-template-columns: 1fr;
  }
}

.selected-tool-info {
  display: flex;
  gap: 14px;
  align-items: flex-start;
  margin-bottom: 20px;
  padding: 16px;
  background: var(--ops-panel-soft);
  border: 1px solid var(--ops-border-soft);
  border-radius: 12px;
}

.selected-tool-info__badge {
  width: 48px;
  height: 48px;
  flex-shrink: 0;
  border-radius: 12px;
  background: rgba(13, 148, 136, 0.12);
  color: var(--ops-primary);
  font-size: 11px;
  font-weight: 700;
  display: flex;
  align-items: center;
  justify-content: center;
  font-family: var(--ops-font-mono);
}

.selected-tool-info h3 {
  margin: 0 0 4px;
  font-size: 16px;
  font-weight: 600;
}

.selected-tool-info p {
  margin: 0;
  font-size: 13px;
  color: var(--ops-text-subtle);
  line-height: 1.5;
}

.no-selection {
  padding: 48px 20px;
}

.no-params-hint {
  padding: 12px 16px;
  margin-bottom: 16px;
  border-radius: 8px;
  background: var(--ops-panel-soft);
  color: var(--ops-text-subtle);
  font-size: 13px;
}

.tool-params h4 {
  margin: 0 0 14px;
  font-size: 14px;
  font-weight: 600;
}

.param-hint {
  display: block;
  font-size: 12px;
  color: var(--ops-text-subtle);
  margin-top: 4px;
}

.execute-actions {
  display: flex;
  gap: 12px;
  margin-top: 24px;
  padding-top: 20px;
  border-top: 1px solid var(--ops-border-soft);
}

.execute-aside {
  display: flex;
  flex-direction: column;
  gap: 12px;
  position: sticky;
  top: 12px;
}

.aside-block {
  padding: 12px 14px;
  border-radius: 10px;
  border: 1px solid var(--ops-border);
  background: var(--ops-panel-soft);
}

.aside-label {
  font-size: 11px;
  font-weight: 600;
  text-transform: uppercase;
  letter-spacing: 0.06em;
  color: var(--ops-text-subtle);
  margin-bottom: 6px;
}

.aside-code {
  display: block;
  font-family: var(--ops-font-mono);
  font-size: 12px;
  color: var(--ops-text);
  word-break: break-all;
  line-height: 1.5;
}

.history-toolbar {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 12px 16px 0;
  gap: 12px;
}

.history-hint {
  font-size: 12px;
  color: var(--ops-text-subtle);
}
</style>
