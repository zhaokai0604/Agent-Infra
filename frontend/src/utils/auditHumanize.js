import { mcpToolDisplayName } from './mcpToolsMeta.js'

const KIND_LABEL = {
  access: '访问',
  dialogue: '对话',
  tool: '工具调用',
  remediation: '处置修复',
  block: '安全拦截',
  confirm: '待确认'
}

const CHANNEL_LABEL = {
  HTTP: '网页接口',
  ASSISTANT: '运维对话',
  CHAT: '运维对话',
  PATROL: '自动巡检',
  RUNBOOK: '运维剧本',
  MCP: '工具调用',
  TOOL: '工具调用'
}

function upper(v) {
  return String(v || '').trim().toUpperCase()
}

function cleanText(text) {
  return String(text || '')
    .replace(/\s+/g, ' ')
    .trim()
}

export function auditKindLabel(kind) {
  return KIND_LABEL[kind] || kind || '记录'
}

export function humanChannel(channel) {
  const raw = String(channel || '').trim()
  if (!raw) return '未知入口'
  return CHANNEL_LABEL[upper(raw)] || CHANNEL_LABEL[raw] || raw
}

export function humanToolName(toolName) {
  if (!toolName || toolName === 'NONE') return ''
  try {
    return mcpToolDisplayName(toolName) || toolName
  } catch {
    return toolName
  }
}

export function formatDurationMs(ms) {
  const n = Number(ms)
  if (!Number.isFinite(n) || n < 0) return ''
  if (n < 1000) return `${Math.round(n)} 毫秒`
  if (n < 60000) return `${(n / 1000).toFixed(n < 10000 ? 1 : 0)} 秒`
  return `${Math.round(n / 60000)} 分 ${Math.round((n % 60000) / 1000)} 秒`
}

export function humanOutcome(row = {}) {
  if (row.auditKind === 'access' || row.httpStatus != null) {
    const s = Number(row.httpStatus ?? row.status)
    if (s >= 500) return '服务异常'
    if (s === 403) return '无权限'
    if (s === 401) return '未登录'
    if (s >= 400) return '请求失败'
    if (s >= 200 && s < 300) return '访问成功'
    return s ? `状态 ${s}` : '已记录'
  }

  const code = upper(row.decision || row.securityOutcome || '')
  if (code.includes('REJECT') || code.includes('BLOCK') || code.includes('DENY')) return '已拦截'
  if (code.includes('NEED_CONFIRM') || code === 'CONFIRM') return '待确认'
  if (code.includes('DRY') || code.includes('PREVIEW') || code.includes('SIMULAT')) return '仅预览'
  if (code.includes('NOOP')) return '无需操作'
  if (code.includes('WARN')) return '完成但有告警'
  if (code.includes('EXECUTE') || code === 'PASS' || code === 'ALLOW') {
    if (row.executionOk === false) return '执行未成功'
    return '已执行'
  }
  if (row.executionOk === true) return '执行成功'
  if (row.executionOk === false) return '执行未成功'
  if (code) return code
  return '已记录'
}

export function outcomeTagType(row = {}) {
  const label = humanOutcome(row)
  if (label.includes('拦截') || label.includes('失败') || label.includes('异常') || label.includes('无权限')) return 'danger'
  if (label.includes('确认') || label.includes('预览') || label.includes('告警')) return 'warning'
  if (label.includes('成功') || label.includes('已执行')) return 'success'
  return 'info'
}

function cleanSummaryText(text) {
  return cleanText(text)
    .replace(/^status=\d+,\s*/i, '')
    .replace(/durationMs=\d+/gi, '')
    .slice(0, 280)
}

export function narrateAudit(row = {}) {
  if (!row || typeof row !== 'object') return '请选择一条记录查看详情'

  const when = row.createdAt
    ? (() => {
        const d = new Date(row.createdAt)
        return Number.isNaN(d.getTime()) ? String(row.createdAt) : d.toLocaleString('zh-CN')
      })()
    : '未知时间'
  const who = row.operatorUserId ? `操作人 ${row.operatorUserId}` : '操作人未知'
  const via = humanChannel(row.requestChannel || row.channel)
  const outcome = humanOutcome(row)
  const tool = humanToolName(row.toolName)
  const target = cleanText(row.targetName || '')
  const dur = formatDurationMs(row.durationMs)
  const durPart = dur ? `，耗时 ${dur}` : ''

  if (row.auditKind === 'access') {
    const ip = row.remoteIp ? `，来源 IP ${row.remoteIp}` : ''
    const role = row.userRole != null && row.userRole !== '' ? `，角色 ${row.userRole}` : ''
    return `${when}，${who}${role} 通过 HTTP 访问 ${target || '接口'}${ip}。结果：${outcome}${durPart}。`
  }

  if (row.auditKind === 'block' || String(outcome).includes('拦截')) {
    const why = cleanSummaryText(row.resultSummary || row.effectSummary || row.userInput)
    return `${when}，${who} 通过 ${via}${tool ? ` 调用 ${tool}` : '发起操作'}，被安全策略拦截${why ? `。原因：${why}` : ''}。`
  }

  if (row.auditKind === 'confirm' || String(outcome).includes('确认')) {
    return `${when}，系统已生成待确认方案${tool ? `（工具：${tool}）` : ''}，需要在对话中确认后执行。`
  }

  if (row.auditKind === 'dialogue') {
    const ask = cleanSummaryText(row.userInput)
    const ans = cleanSummaryText(row.resultSummary || row.effectSummary)
    return `${when}，${who} 发起运维对话${ask ? `：${ask}` : ''}。${ans ? `结论：${ans}。` : `结果：${outcome}。`}${dur ? `耗时 ${dur}。` : ''}`
  }

  const action = tool
    ? `调用工具 ${tool}`
    : target
      ? `操作目标 ${target}`
      : '执行运维动作'
  const effect = cleanSummaryText(row.effectSummary || row.resultSummary)
  return `${when}，${who} 通过 ${via} ${action}。结果：${outcome}${durPart}${effect ? `。摘要：${effect}` : ''}。`
}

export function humanStepTitle(step, index = 0) {
  if (!step || typeof step !== 'object') return `第 ${index + 1} 步`
  const rawPhase = String(step.phase || step.title || step.name || '').trim()
  const phase = rawPhase.toLowerCase()
  if (phase === 'cot') {
    const detail = String(step.detail || step.message || step.summary || '')
    const matched = detail.match(/^\[Step\s*\d+\s*-\s*([^\]]+)\]/i)
    if (matched?.[1]) return matched[1].trim()
    return '分析推理'
  }
  const map = {
    request: '发起请求',
    identity: '身份核对',
    result: '返回结果',
    plan: '制定计划',
    confirm: '等待确认',
    execute: '执行动作',
    tool: '调用工具',
    security: '安全检查',
    gate: '安全门禁',
    reply: '生成回复',
    rag: '检索知识',
    think: '分析判断',
    cot: '分析推理'
  }
  if (map[phase]) return map[phase]
  if (/[\u4e00-\u9fff]/.test(phase)) return phase
  return phase || `第 ${index + 1} 步`
}

export function humanStepBody(step) {
  if (!step || typeof step !== 'object') return String(step || '')
  if (typeof step.detail === 'string' && step.detail.trim()) return step.detail.trim()
  if (typeof step.message === 'string' && step.message.trim()) return step.message.trim()
  if (typeof step.summary === 'string' && step.summary.trim()) return step.summary.trim()
  if (step.detail && typeof step.detail === 'object') {
    const bits = []
    if (step.detail.toolName) bits.push(`工具：${humanToolName(step.detail.toolName)}`)
    if (step.detail.decision) bits.push(`判断：${step.detail.decision}`)
    if (step.detail.path) bits.push(`路径：${step.detail.path}`)
    if (bits.length) return bits.join('，')
    return JSON.stringify(step.detail, null, 2)
  }
  const { phase, title, name, ...rest } = step
  const keys = Object.keys(rest)
  if (!keys.length) return '（无更多说明）'
  if (keys.length <= 4) {
    return keys.map((k) => `${k}：${typeof rest[k] === 'object' ? JSON.stringify(rest[k]) : rest[k]}`).join('\n')
  }
  return JSON.stringify(rest, null, 2)
}
