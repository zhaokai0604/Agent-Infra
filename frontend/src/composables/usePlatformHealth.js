import { computed, ref } from 'vue'
import { getPlatformInfo, getKnowledgeStatus, getMcpTools } from '../api'
import { mergeToolRegistry } from '../utils/mcpToolsMeta'
import { detectWindows } from '../utils/platformQuickPaths'

let sharedPlatform = ref(null)
let sharedKnowledge = ref(null)
let sharedMcpTools = ref([])
let sharedMcpError = ref(false)
let loading = ref(false)
let lastFetchAt = 0
const CACHE_MS = 45000

async function fetchHealth(force = false) {
  const now = Date.now()
  if (!force && lastFetchAt && now - lastFetchAt < CACHE_MS) {
    return
  }
  loading.value = true
  sharedMcpError.value = false
  try {
    const [p, k, tools] = await Promise.all([
      getPlatformInfo().catch(() => null),
      getKnowledgeStatus().catch(() => null),
      getMcpTools().catch(() => {
        sharedMcpError.value = true
        return null
      })
    ])
    sharedPlatform.value = p
    sharedKnowledge.value = k
    sharedMcpTools.value = mergeToolRegistry(tools, { registryLoaded: tools != null })
    lastFetchAt = Date.now()
  } finally {
    loading.value = false
  }
}

export function usePlatformHealth() {
  const platform = sharedPlatform
  const knowledge = sharedKnowledge
  const mcpTools = sharedMcpTools
  const mcpLoadError = sharedMcpError

  const mcpOnlineCount = computed(() => mcpTools.value.filter(t => t.status === 'online').length)
  const mcpTotal = computed(() => mcpTools.value.length)

  const runtimeMode = computed(() => {
    const sec = platform.value?.security
    const rt = platform.value?.runtime
    const win = detectWindows(platform.value)
    const modes = []
    if (sec?.globalDryRun) {
      modes.push({ key: 'dryrun', label: '演练', type: 'warning', tip: '全局演练模式：写操作仅预览' })
    }
    if (sec?.aiConfigured === false) {
      modes.push({ key: 'noai', label: '无 AI', type: 'info', tip: 'AI 未配置：运维工具与巡检仍可用' })
    } else if (sec?.aiConfigured) {
      modes.push({ key: 'ai', label: 'AI', type: 'success', tip: 'AI 已配置就绪' })
    }
    if (mcpOnlineCount.value > 0) {
      modes.push({
        key: 'mcp',
        label: `工具 ${mcpOnlineCount.value}/${mcpTotal.value}`,
        type: 'success',
        tip: `${mcpOnlineCount.value} 个运维工具在线`
      })
    } else if (mcpLoadError.value) {
      modes.push({ key: 'mcp', label: '工具离线', type: 'danger', tip: '无法连接运维工具服务' })
    }
    if (rt?.dbReachable === false) {
      modes.push({ key: 'db', label: 'DB', type: 'danger', tip: '数据库未连接' })
    }
    if (win) modes.push({ key: 'os', label: 'Win', type: 'info', tip: 'Windows 环境' })
    else modes.push({ key: 'os', label: 'Linux', type: 'info', tip: 'Linux 环境' })
    return modes
  })

  const healthIssues = computed(() => {
    const list = []
    const sec = platform.value?.security
    if (sec?.aiConfigured === false) {
      list.push({ key: 'ai', text: 'AI 未配置：智能对话不可用，运维工具与巡检仍可用', tab: 'system-config', section: 'ai', actionLabel: '配置 AI Key' })
    }
    if (knowledge.value?.enabled && !knowledge.value?.qdrantConnected) {
      list.push({ key: 'qdrant', text: 'Qdrant 未连接：知识库 RAG 不可用', tab: 'knowledge', actionLabel: '知识库' })
    }
    if (sec?.globalDryRun) {
      list.push({ key: 'dryrun', text: '全局演练模式：写操作仅预览', tab: 'system-config', actionLabel: '系统配置' })
    }
    if (platform.value?.runtime?.dbReachable === false) {
      list.push({ key: 'db', text: '数据库未连接：日志分析/审计/历史不可用', tab: 'environment-status', actionLabel: '环境状态' })
    }
    if (!platform.value) {
      list.push({ key: 'api', text: '无法读取平台信息', tab: 'environment-status', actionLabel: '环境状态' })
    }
    return list
  })

  return {
    platform,
    knowledge,
    mcpTools,
    mcpLoadError,
    mcpOnlineCount,
    mcpTotal,
    runtimeMode,
    healthIssues,
    loading,
    refresh: fetchHealth
  }
}
