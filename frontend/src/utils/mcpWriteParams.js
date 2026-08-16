/**
 * MCP 写操作参数统一口径（工具控制台 / 运维对话 / confirmExecute 共用）。
 * 与后端 McpToolParamReader.forceConfirmedWriteToolParams 对齐。
 */

export const REAL_WRITE_RE =
  /真实删除|彻底删除|真正删除|不要预览|不要dry-?run|执行删除|真实重启|立即重启|直接删除|立即删除|确认执行|confirm\s*[:=]\s*true|删掉|删了/i

export const DELETE_UTTERANCE_RE = /删除|删掉|移除|清除|清理|释放|删了/i

export function userRequestedRealWrite(parameters, userMessage) {
  const p = parameters || {}
  if (p.confirmDelete || p.confirmRestart || p.confirmKill || p.confirmStop) return true
  if (p.dryRun === false) return true
  const text = (userMessage || '').trim()
  return text.length > 0 && REAL_WRITE_RE.test(text)
}

export function isTempSubpath(path) {
  if (!path) return false
  return /\/tmp\/|\/var\/tmp\/|\/var\/temp\//.test(String(path).replace(/\\/g, '/'))
}

export function applyMcpWriteConfirmParams(toolName, parameters, userMessage) {
  const p = { ...(parameters || {}) }
  const confirmed =
    userMessage === '确认执行' || userRequestedRealWrite(p, userMessage)
  if (!confirmed) {
    return p
  }

  const op = String(p.operation || '').trim().toLowerCase()
  if (toolName === 'CleanTempTool' || toolName === 'LogCleanupTool') {
    p.dryRun = false
    p.confirmDelete = true
    if (toolName === 'CleanTempTool' && isTempSubpath(p.path)) {
      p.removeDirectory = true
      p.days = 0
    }
  } else if (toolName === 'DiskOpsTool' && ['clean-temp', 'clean', 'cleanup'].includes(op)) {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'LogOpsTool' && ['cleanup', 'clean', 'prune'].includes(op)) {
    p.dryRun = false
    p.confirmDelete = true
  } else if (toolName === 'ServiceRestartTool' || (toolName === 'ServiceOpsTool' && op === 'restart')) {
    p.dryRun = false
    p.confirmRestart = true
  } else if ((toolName === 'DockerTool' || toolName === 'ContainerOpsTool') && op === 'restart') {
    p.dryRun = false
    p.confirmRestart = true
  } else if ((toolName === 'DockerTool' || toolName === 'ContainerOpsTool') && op === 'stop') {
    p.dryRun = false
    p.confirmStop = true
  } else if ((toolName === 'ProcessOpsTool' || toolName === 'ProcessTool') && ['kill', 'terminate', 'stop'].includes(op)) {
    p.dryRun = false
    p.confirmKill = true
  } else if (toolName === 'SystemdTool' && op === 'restart') {
    p.dryRun = false
    p.confirmRestart = true
  } else if (toolName === 'RemoteCleanTempTool') {
    p.dryRun = false
    p.confirmDelete = true
  }

  return p
}

/** 从自然语言解析 CleanTempTool 初始参数（对话直调 MCP 路径）。 */
export function extractCleanTempParamsFromMessage(message) {
  const params = {}
  const lowerMsg = (message || '').toLowerCase()
  const pathMatch = message.match(/\/[\w\/.-]+/)
  if (pathMatch) {
    params.path = pathMatch[0]
    params.removeDirectory = DELETE_UTTERANCE_RE.test(message)
  }
  const daysMatch = message.match(/(\d+)\s*天/)
  if (daysMatch) {
    params.days = parseInt(daysMatch[1], 10)
  } else {
    params.days = params.removeDirectory ? 0 : 7
  }
  const wantRealDelete = REAL_WRITE_RE.test(message) || DELETE_UTTERANCE_RE.test(message)
  const wantPreview = lowerMsg.includes('预览') || lowerMsg.includes('dry-run')
  params.dryRun = wantPreview || !wantRealDelete
  params.confirmDelete = wantRealDelete && !wantPreview
  if (params.removeDirectory && isTempSubpath(params.path)) {
    params.days = 0
  }
  return params
}
