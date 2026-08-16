import axios from 'axios'
import { ElMessage } from 'element-plus'
import { formatApiError } from '../utils/apiError.js'

// DELIVERY: 部分 export 为死封装/无挂载页，保留以免破坏脚本；见 docs/deployment/交付API白名单.md

/** 生产/同源部署：构建或 .env 中设置 VITE_AWARD_LOG_BASE_URL（须含 /award-log 后缀，无末尾斜杠） */
const viteAwardBase =
  typeof import.meta !== 'undefined' && import.meta.env?.VITE_AWARD_LOG_BASE_URL
    ? String(import.meta.env.VITE_AWARD_LOG_BASE_URL).trim().replace(/\/$/, '')
    : ''

/**
 * 解析 API 基址（单端口 8088）：
 * - dev：当前页同源 + /award-log（Vite 代理到 8088）
 * - 生产：VITE_AWARD_LOG_BASE_URL 或当前页同源 + /award-log
 */
function buildRuntimeBaseUrl() {
  if (typeof window !== 'undefined' && import.meta.env?.DEV) {
    const { protocol, hostname, port } = window.location
    const portPart = port ? `:${port}` : ''
    return `${protocol}//${hostname}${portPart}/award-log`
  }
  if (viteAwardBase) {
    return viteAwardBase.replace(/\/$/, '')
  }
  if (typeof window === 'undefined') {
    return 'http://localhost:8088/award-log'
  }
  const { protocol, hostname, port } = window.location
  if (port === '8088') {
    return `${protocol}//${hostname}:8088/award-log`
  }
  const portPart = port ? `:${port}` : ''
  return `${protocol}//${hostname}${portPart}/award-log`
}

/** 管理类接口与业务同基址（单端口，不再分流 8089） */
function buildManagementBaseUrl() {
  return buildRuntimeBaseUrl()
}

const API_TIMEOUT_MS = (() => {
  const raw = typeof import.meta !== 'undefined' ? import.meta.env?.VITE_API_TIMEOUT_MS : undefined
  const n = parseInt(String(raw ?? '600000'), 10)
  return Number.isFinite(n) && n >= 30000 ? n : 600000
})()

/** fetch 流式请求须与 axios 一致带头，否则 SessionCsrfGuardFilter 在非 localhost 来源下返回 403 */
const FETCH_JSON_HEADERS = {
  'Content-Type': 'application/json',
  'X-Requested-With': 'XMLHttpRequest'
}

function createClient(baseUrlResolver) {
  const client = axios.create({
    timeout: API_TIMEOUT_MS,
    withCredentials: true,
    headers: {
      'X-Requested-With': 'XMLHttpRequest'
    }
  })
  client.interceptors.request.use((config) => {
    config.baseURL = typeof baseUrlResolver === 'function' ? baseUrlResolver() : baseUrlResolver
    return config
  })
  return client
}

const request = createClient(buildRuntimeBaseUrl)
const managementRequest = createClient(buildManagementBaseUrl)

function buildApiError(message, data) {
  const wrapped = new Error(message || '请求失败')
  wrapped.securityCode = data?.securityCode
  wrapped.raw = data
  return wrapped
}

function attachInterceptors(client) {
  client.interceptors.response.use(
    res => {
      if (res.config?.responseType === 'blob') {
        return res.data
      }
      const reqUrl = String(res.config?.url || '')
      // MCP 工具接口返回 Spring Map JSON（success / needConfirm / error），无统一 { code, data }
      if (reqUrl.includes('/mcp/')) {
        return res.data
      }
      const { code, data, message } = res.data
      if (code === 200) return data
      const errMsg = formatApiError({ response: { data: res.data } }) || message || '请求失败'
      if (code === 401) {
        localStorage.removeItem('user')
        ElMessage.error(errMsg)
        setTimeout(() => window.location.reload(), 300)
        return Promise.reject(buildApiError(errMsg, res.data))
      }
      if (!res.config?.silent) {
        ElMessage.error(errMsg)
      }
      return Promise.reject(buildApiError(errMsg, res.data))
    },
    error => {
      const status = error.response?.status
      const securityCode = error.response?.data?.securityCode
      // 单端口架构下不应再出现跨端口重试；若仍收到旧双端口 403，给出明确提示
      if (status === 403 && (securityCode === 'MANAGEMENT_PORT_REQUIRED' || securityCode === 'BUSINESS_PORT_REQUIRED')) {
        const hint =
          '检测到双端口隔离响应，但当前应为单端口 8088。请确认后端 app.management.enabled=false 并重启。'
        if (!error.config?.silent) {
          ElMessage.error(hint)
        }
        return Promise.reject(buildApiError(hint, error.response?.data))
      }
      const errMsg = formatApiError(error)
      if (status === 401) {
        localStorage.removeItem('user')
        ElMessage.error('登录已过期，请重新登录')
        setTimeout(() => window.location.reload(), 300)
        return Promise.reject(buildApiError(errMsg, error.response?.data))
      }
      if (!error.config?.silent) {
        ElMessage.error(errMsg)
      }
      return Promise.reject(buildApiError(errMsg, error.response?.data))
    }
  )
}

attachInterceptors(request)
attachInterceptors(managementRequest)

export { formatApiError }

export const uploadLog = (formData) => request.post('/log/upload', formData)

export const uploadLogs = (formData) => request.post('/log/upload/multi', formData)

export const getTaskStatus = (taskId) => request.get(`/log/task/${taskId}`)
export const getReport = (taskId) => request.get(`/log/report/${taskId}`)
/** 大数据：分页查明细；anomalyOnly=true 只拉异常 */
export const getReportDetails = (taskId, pageNum = 1, pageSize = 50, anomalyOnly = false) =>
  request.get(`/log/report/${taskId}/details`, { params: { pageNum, pageSize, anomalyOnly } })
export const getHistory = (pageNum = 1, pageSize = 10, filters = {}) => {
  const params = { pageNum, pageSize }
  for (const [k, v] of Object.entries(filters || {})) {
    if (v != null && String(v).trim() !== '') {
      params[k] = typeof v === 'string' ? v.trim() : v
    }
  }
  return request.get('/log/history', { params })
}
export const performDiagnosis = (taskId) => request.post(`/log/diagnose/${taskId}`)
// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
export const pauseTask = (taskId) => request.post(`/log/pause/${taskId}`)
export const resumeTask = (taskId) => request.post(`/log/resume/${taskId}`)
export const cancelTask = (taskId) => request.post(`/log/cancel/${taskId}`)
export const deleteTask = (taskId) => request.delete(`/log/delete/${taskId}`)
export const downloadReport = (taskId, type) =>
  request.get(`/log/download/${encodeURIComponent(taskId)}/${encodeURIComponent(type)}`, { responseType: 'blob' })

// 用户管理相关接口
export const login = (user) => managementRequest.post('/admin/user/login', user)
export const logout = () => managementRequest.post('/admin/user/logout')
export const register = (user) => request.post('/admin/user/register', user)
// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
export const getUserById = (userId) => managementRequest.get(`/admin/user/${userId}`)
export const updateUser = (user) => managementRequest.put('/admin/user', user)
export const deleteUser = (userId) => managementRequest.delete(`/admin/user/${userId}`)
export const getUsers = () => managementRequest.get('/admin/user/list')
export const getUsersPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/user/page', { params: { pageNum, pageSize } })
// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（忘记密码未闭环）
export const checkUserExists = (userInfo) => request.post('/admin/user/check-user', userInfo)
export const resetPassword = (passwordInfo) => managementRequest.post('/admin/user/reset-password', passwordInfo)

// 数据统计相关接口（log-summary / performance / task-status 为交付主链路）
export const getLogSummary = (days = 7) =>
  managementRequest.get('/admin/statistics/log-summary', { params: { days } })
export const getPerformance = (networkInterface = '') =>
  managementRequest.get('/admin/statistics/performance', { params: { networkInterface } })
// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
export const getAnomalyStatistics = (days = 7) =>
  managementRequest.get('/admin/statistics/anomaly-statistics', { params: { days } })
export const getTaskStatusStatistics = () => managementRequest.get('/admin/statistics/task-status')

// 性能：交付主链路用 statistics/performance；下方 /api/performance/* 为扩展死封装
export const getPerformanceData = (config = {}) => managementRequest.get('/admin/statistics/performance', config)
export const getPatrolAlertsRecent = (limit = 12, config = {}) =>
  managementRequest.get('/api/ops/patrol/alerts/recent', { ...config, params: { ...(config.params || {}), limit } })

export const getPatrolCorrelationLatest = (config = {}) =>
  managementRequest.get('/api/ops/patrol/correlation/latest', config)

export const getPatrolRemediationPending = (config = {}) =>
  managementRequest.get('/api/ops/patrol/remediation/pending', config)

export const confirmPatrolRemediation = (proposalId, confirmCode = '确认执行') =>
  managementRequest.post('/api/ops/patrol/remediation/confirm', { proposalId, confirmCode })

export const getPatrolRemediationCoverage = (config = {}) =>
  managementRequest.get('/api/ops/patrol/remediation/coverage', config)

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（/api/performance 扩展）
export const collectSystemPerformanceData = () => managementRequest.post('/api/performance/collect-system-data')

export const getPerformanceTrend = (dataType, startTime, endTime, interval = 5) =>
  managementRequest.get('/api/performance/trend', {
    params: { dataType, startTime, endTime, interval }
  })

export const getPerformanceMultiDimension = (startTime, endTime, interval = 10) =>
  managementRequest.get('/api/performance/multi-dimension', {
    params: { startTime, endTime, interval }
  })

export const getAssistantContext = () => managementRequest.get('/api/assistant/context')
export const getAssistantModels = () => managementRequest.get('/api/assistant/models')
export const getAssistantStatePreview = (message, history = [], options = {}) =>
  managementRequest.post('/api/assistant/state/preview', {
    message,
    history: Array.isArray(history) ? history : [],
    useToolAgent: options.useToolAgent !== false,
    confirmRemediation: options.confirmRemediation === true
  })

/** 统一运维助手：流式自然语言对话（含可选多轮 history） */
// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
/** 延时执行本地工具任务（与 /api/mcp 同源；服务重启后任务丢失） */
export const scheduleDeferredOpsTask = (payload) =>
  managementRequest.post('/api/ops-schedule/tasks', payload)

export const listDeferredOpsTasks = () => managementRequest.get('/api/ops-schedule/tasks')

export const cancelDeferredOpsTask = (taskId) =>
  managementRequest.delete(`/api/ops-schedule/tasks/${taskId}`)
export const assistantChatStream = (message, history = [], options = {}) =>
  fetch(`${getManagementApiBaseUrl()}/api/assistant/chat/stream`, {
    method: 'POST',
    headers: FETCH_JSON_HEADERS,
    credentials: 'include',
    signal: options.signal,
    body: JSON.stringify({
      message,
      history: Array.isArray(history) ? history : [],
      useToolAgent: options.useToolAgent !== false,
      confirmRemediation: options.confirmRemediation === true,
      modelProfile: options.modelProfile ? String(options.modelProfile) : undefined,
      targetHostId: options.targetHostId != null && options.targetHostId !== ''
        ? Number(options.targetHostId)
        : undefined
    })
  })
export const getApiBaseUrl = () => buildRuntimeBaseUrl()
export const getManagementApiBaseUrl = () => buildManagementBaseUrl()

export function buildManagementWsUrl(pathname = '/ws/performance') {
  const wsPath = pathname.startsWith('/') ? pathname : `/${pathname}`
  if (typeof window === 'undefined') {
    const base = getManagementApiBaseUrl().replace(/^http/, 'ws')
    return `${base}${wsPath}`
  }
  const base = new URL(getManagementApiBaseUrl())
  const wsScheme = base.protocol === 'https:' ? 'wss:' : 'ws:'
  return `${wsScheme}//${base.host}${base.pathname}${wsPath}`
}

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（/api/performance 扩展）
export const getPerformanceBaseline = (dataType, days = 7) =>
  managementRequest.get('/api/performance/baseline', {
    params: { dataType, days }
  })

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（告警无默认页）
export const getAlarmConfig = (taskId = '') =>
  taskId ? request.get(`/api/alarm/config/${taskId}`) : request.get('/api/alarm/config')

export const saveAlarmConfig = (config, taskId = '') =>
  taskId ? request.post(`/api/alarm/config/${taskId}`, config) : request.post('/api/alarm/config', config)

/** MCP 路径/服务白名单（系统配置中心可一并使用；独立弹窗组件为遗留） */
export const getAgentPathPolicy = () => managementRequest.get('/api/agent/path-policy')
export const saveAgentPathPolicy = (body) => managementRequest.put('/api/agent/path-policy', body)

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（RBAC 无 UI）
export const getRoleById = (roleId) => managementRequest.get(`/admin/role/${roleId}`)
export const getRoleByRoleName = (roleName) => managementRequest.get(`/admin/role/name/${roleName}`)
export const getRoles = () => managementRequest.get('/admin/role/list')
export const getRolesPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/role/page', { params: { pageNum, pageSize } })
export const addRole = (role) => managementRequest.post('/admin/role', role)
export const updateRole = (role) => managementRequest.put('/admin/role', role)
export const deleteRole = (roleId) => managementRequest.delete(`/admin/role/${roleId}`)

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（RBAC 无 UI）
export const getPermissionById = (permissionId) => managementRequest.get(`/admin/permission/${permissionId}`)
export const getPermissionByPermissionCode = (permissionCode) => managementRequest.get(`/admin/permission/code/${permissionCode}`)
export const getPermissions = () => managementRequest.get('/admin/permission/list')
export const getPermissionsPage = (pageNum = 1, pageSize = 10) =>
  managementRequest.get('/admin/permission/page', { params: { pageNum, pageSize } })
export const getPermissionsByRoleId = (roleId) => managementRequest.get(`/admin/permission/role/${roleId}`)
export const addPermission = (permission) => managementRequest.post('/admin/permission', permission)
export const updatePermission = (permission) => managementRequest.put('/admin/permission', permission)
export const deletePermission = (permissionId) => managementRequest.delete(`/admin/permission/${permissionId}`)
export const saveRolePermissions = (roleId, permissionIds) => managementRequest.post(`/admin/role/${roleId}/permissions`, permissionIds)

// 个人中心相关接口
export const getUserInfo = () => request.get('/api/profile/user-info')
export const updateUserInfo = (user) => request.put('/api/profile/user-info', user)
export const changeUserPassword = (passwordInfo) => request.post('/api/profile/change-password', passwordInfo)
export const getAccessTrail = (page = 1, pageSize = 10) =>
  request.get('/api/profile/access-trail', { params: { page, pageSize } })
export const getLoginHistory = (page = 1, pageSize = 10) =>
  request.get('/api/profile/login-history', { params: { page, pageSize } })
export const generateApiKey = () => request.post('/api/profile/generate-api-key')
export const getUserStats = () => request.get('/api/profile/user-stats')
export const getUserApiKeys = () => request.get('/api/profile/api-keys')
export const createUserApiKey = (payload) => request.post('/api/profile/api-keys', payload)
export const rotateUserApiKey = (id) => request.post(`/api/profile/api-keys/${id}/rotate`)
export const revokeUserApiKey = (id) => request.post(`/api/profile/api-keys/${id}/revoke`)
export const getSystemConfigEffective = () => managementRequest.get('/api/system-config/effective')
export const saveSystemConfigEffective = (payload) => managementRequest.put('/api/system-config/effective', payload)
export const reconcileSystemBootstrap = () => managementRequest.post('/api/system-config/bootstrap/reconcile')
export const getPlatformInfo = (config = {}) => managementRequest.get('/api/platform/info', config)

/** 公开验收探针（无需登录；运维/交付验收用，非默认导航） */
export const getPlatformAcceptance = () => request.get('/api/platform/acceptance')

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（告警历史）
export const getAlarmHistory = (pageNum = 1, pageSize = 10, level = '', taskId = '') =>
  request.get('/api/alarm/history/list', { params: { pageNum, pageSize, level, taskId } })

const alarmFilterParams = (days, level, taskId) => {
  const p = { days }
  if (level) p.level = level
  if (taskId) p.taskId = taskId
  return p
}

export const getAlarmStatistics = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/statistics', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmTrend = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/trend', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmLevelDistribution = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/level-distribution', { params: alarmFilterParams(days, level, taskId) })

export const getAlarmRootCauseStatistics = (days = 7, level = '', taskId = '') =>
  request.get('/api/alarm/history/root-cause-statistics', { params: alarmFilterParams(days, level, taskId) })

export const processAlarmsByTaskId = (taskId) =>
  request.get('/api/alarm/history/process-by-task', { params: { taskId } })

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（告警规则兼容 API）
export const addAlarmRule = (rule) => request.post('/api/alarm-rule/add', rule)
export const updateAlarmRule = (rule) => request.post('/api/alarm-rule/update', rule)
export const deleteAlarmRule = (id) => request.delete(`/api/alarm-rule/delete/${id}`)
export const getAlarmRuleById = (id) => request.get(`/api/alarm-rule/get/${id}`)
export const getAllAlarmRules = () => request.get('/api/alarm-rule/list')
export const getEnabledAlarmRules = () => request.get('/api/alarm-rule/enabled')
export const enableAlarmRule = (id, enabled) => request.post('/api/alarm-rule/enable', null, { params: { id, enabled } })
export const testAlarmRule = (ruleId, testContent) => request.post('/api/alarm-rule/test', null, { params: { ruleId, testContent } })

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
export const getCleanRules = () => request.get('/api/log/clean/rules')
export const saveCleanRules = (rules) => request.post('/api/log/clean/rules', rules)
export const cleanLog = (logContent, rules) => request.post('/api/log/clean', { logContent, rules })

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md
export const getESLogs = (pageNum = 1, pageSize = 10) =>
  request.get('/api/elasticsearch/logs', { params: { pageNum, pageSize } })

export const indexESLogs = (logs) => request.post('/api/elasticsearch/index', logs)

export const searchESLogs = (
  query,
  severity = '',
  pageNum = 1,
  pageSize = 10,
  startTime = '',
  endTime = '',
  anomaly = ''
) => request.get('/api/elasticsearch/search', {
  params: { query, severity, pageNum, pageSize, startTime, endTime, anomaly }
})

export const getESLogById = (id) => request.get(`/api/elasticsearch/get/${id}`)

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（Env 不直打专端）
export const getModelRfHealth = () => managementRequest.get('/api/v1/model/health')
export const getCollectorStatus = () => managementRequest.get('/api/collector/status')
export const getKafkaStatus = () => managementRequest.get('/api/kafka/status')

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（AiAuditCenter 未挂载）
export const getAiAuditRecent = (limit = 100) =>
  managementRequest.get('/admin/audit/ai/recent', { params: { limit } })

/** 运维侧全链路审计（ops_audit_trace） */
export const getAuditFeed = (limit = 100, kind = '') =>
  managementRequest.get('/api/audit/feed', {
    params: {
      limit,
      ...(kind && kind !== 'all' ? { kind } : {})
    }
  })

export const getAuditDetail = ({ entryId = '', traceId = '' } = {}) =>
  managementRequest.get('/api/audit/detail', {
    params: {
      ...(entryId ? { entryId } : {}),
      ...(traceId ? { traceId } : {})
    }
  })

export const getOpsTraceRecent = (limit = 100, config = {}) =>
  managementRequest.get('/api/ops-trace/recent', { ...config, params: { ...(config.params || {}), limit } })

/** 单条审计含 steps_json（思维链） */
export const getOpsTraceDetail = (traceId) =>
  managementRequest.get('/api/ops-trace/detail', { params: { traceId } })

// DELIVERY: 死封装/无挂载页 — 见 docs/deployment/交付API白名单.md（Runbook 无 UI）
export const getRunbookList = () => managementRequest.get('/api/runbook/list')
export const submitRunbook = (payload) => managementRequest.post('/api/runbook/submit', payload)
export const approveRunbook = (id) => managementRequest.post(`/api/runbook/${id}/approve`)
export const rejectRunbook = (id, reason = '') => managementRequest.post(`/api/runbook/${id}/reject`, { reason })
export const executeRunbook = (id) => managementRequest.post(`/api/runbook/${id}/execute`)

export const submitDecisionFeedback = (feedback) => request.post('/api/decision-feedback/submit', feedback)
export const submitDecisionFeedbackBatch = (feedbacks) => request.post('/api/decision-feedback/submit/batch', feedbacks)
export const getUntrainedSamples = (pageNum = 1, pageSize = 20) =>
  request.get('/api/decision-feedback/untrained', { params: { pageNum, pageSize } })
export const getUntrainedCount = () => request.get('/api/decision-feedback/untrained/count')
export const triggerManualTraining = () => request.post('/api/decision-feedback/train/manual')
export const markAsTrained = (ids) => request.post('/api/decision-feedback/mark-trained', ids)

// MCP 工具相关 API
export const executeMcpTool = (toolName, parameters, userMessage = '') => {
  const body = { toolName, parameters }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  return managementRequest.post('/api/mcp/execute', body)
}

/** 中等风险二次确认后执行（confirmCode 须与后端一致：确认执行） */
export const confirmMcpExecute = (
  toolName,
  parameters,
  confirmCode = '确认执行',
  userMessage = '',
  confirmationId = '',
  capabilityToken = ''
) => {
  const body = { toolName, parameters, confirmCode }
  const um = typeof userMessage === 'string' ? userMessage.trim() : ''
  if (um) body.userMessage = um
  const cid = typeof confirmationId === 'string' ? confirmationId.trim() : ''
  if (cid) body.confirmationId = cid
  const token = typeof capabilityToken === 'string' ? capabilityToken.trim() : ''
  if (token) body.capabilityToken = token
  return managementRequest.post('/api/mcp/confirmExecute', body)
}

export const getMcpTools = () => managementRequest.get('/api/mcp/tools')

/** 一键安全自检（探针走真实安全门，不执行系统命令） */
export const getSecuritySelfCheck = () => managementRequest.get('/api/security/self-check')

/** 当前安全策略快照（路径策略版本 / 风险阈值 / 治理开关） */
export const getSecurityPolicySnapshot = () => managementRequest.get('/api/security/policy-snapshot')

/** 单工具策略回放 */
export const replaySecurityPolicy = (toolName, parameters = {}, userMessage = '', profile = 'INITIAL_REQUEST') =>
  managementRequest.post('/api/security/policy-replay', {
    toolName,
    parameters,
    userMessage,
    profile
  })

/** 多步计划效果图裁决 */
export const replaySecurityPlan = (steps = []) =>
  managementRequest.post('/api/security/policy-replay/plan', { steps })

/** 治理覆盖前后对比 */
export const compareSecurityPolicy = (toolName, parameters = {}, userMessage = '', profile = 'INITIAL_REQUEST') =>
  managementRequest.post('/api/security/policy-replay/compare', {
    toolName,
    parameters,
    userMessage,
    profile
  })

/** 按审计 traceId 回放裁决 */
export const replaySecurityAudit = (traceId) =>
  managementRequest.get(`/api/security/policy-replay/audit/${encodeURIComponent(traceId || '')}`)

export const getPatrolHistory = (days = 7, limit = 50) =>
  managementRequest.get('/api/ops/patrol/history', { params: { days, limit } })

export const getPatrolHistoryTrend = (days = 7, config = {}) =>
  managementRequest.get('/api/ops/patrol/history/trend', { ...config, params: { ...(config.params || {}), days } })

export const getPatrolMetricsTrend = (days = 7, limit = 500, config = {}) =>
  managementRequest.get('/api/ops/patrol/history/metrics-trend', {
    ...config,
    params: { ...(config.params || {}), days, limit }
  })

/** 运维效果评分仪表盘 */
export const getOpsEffectDashboard = (days = 7) =>
  managementRequest.get('/api/ops/effect/dashboard', { params: { days } })

export const getOpsWorkflowMemory = (domain = 'all', q = '') =>
  managementRequest.get('/api/ops/workflow/memory', {
    params: { domain, ...(q ? { q } : {}) }
  })

export const getOpsWorkflowRuns = (workflowId, limit = 8) =>
  managementRequest.get('/api/ops/workflow/runs', {
    params: { workflowId, limit }
  })

export const getOpsFailureInsights = (q = '', limit = 12) =>
  managementRequest.get('/api/ops/workflow/failure-insights', {
    params: { limit, ...(q ? { q } : {}) }
  })

/** 工作台本地拦截后上报 Reflexion 教训 */
export const captureOpsFailureInsight = (payload) =>
  managementRequest.post('/api/ops/workflow/failure-insights/capture', payload, { silent: true })

export const induceOpsWorkflowFromAudit = (limit = 20) =>
  managementRequest.post('/api/ops/workflow/induce-from-audit', null, { params: { limit } })

// DELIVERY: 死封装/无挂载页 — 巡检内部/可选（见 docs/deployment/交付API白名单.md）
export const runAutonomousOps = (forceRemediate = false, readOnly = false) =>
  managementRequest.post('/api/ops/autonomous/run', null, {
    params: { forceRemediate, readOnly }
  })

// 知识库（RAG）
export const getKnowledgeStatus = () => request.get('/api/v1/knowledge/status')

export const seedKnowledgeBuiltin = () => request.post('/api/v1/knowledge/seed')

export const listKnowledgeDocuments = (page = 1, pageSize = 20) =>
  request.get('/api/v1/knowledge/documents', { params: { page, pageSize } })

export const uploadKnowledgeText = (payload) =>
  request.post('/api/v1/knowledge/upload', payload)

export const uploadKnowledgeFile = (formData) =>
  request.post('/api/v1/knowledge/upload/file', formData)

export const searchKnowledge = (query, topK = 5) =>
  request.get('/api/v1/knowledge/search', { params: { query, topK } })

export const deleteKnowledgeDocument = (documentId) =>
  request.delete(`/api/v1/knowledge/document/${encodeURIComponent(documentId)}`)
