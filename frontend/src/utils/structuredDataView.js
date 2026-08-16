/**
 * 把 API / 工具返回整理成可视化结构，避免页面直接堆原始 JSON。
 */

import { normalizeMcpToolResponse } from './mcpToolResult'

const FIELD_LABELS = {
  mode: '模式',
  path: '路径',
  plan: '计划',
  note: '说明',
  reason: '原因',
  message: '消息',
  detail: '详情',
  title: '标题',
  summary: '摘要',
  resultSummary: '结果摘要',
  effectSummary: '效果摘要',
  securityOutcome: '安全结论',
  securityCode: '安全码',
  riskScore: '风险分',
  riskLevel: '风险级别',
  riskExplanation: '风险说明',
  duration: '耗时(ms)',
  durationMs: '耗时(ms)',
  traceId: 'traceId',
  trace_id: 'traceId',
  targetName: '目标',
  toolName: '工具',
  channel: '通道',
  requestChannel: '请求通道',
  executionOk: '执行成功',
  executionStatus: '执行状态',
  success: '成功',
  error: '错误',
  status: '状态',
  statusCode: '状态码',
  statusText: '状态描述',
  createdAt: '时间',
  operatorUserId: '操作人',
  remoteIp: '来源 IP',
  userRole: '角色',
  command: '命令',
  output: '输出',
  service: '服务',
  pid: 'PID',
  cpu: 'CPU%',
  mem: '内存%',
  cpuUsagePercent: 'CPU 使用率',
  memUsagePercent: '内存使用率',
  usePercent: '使用率',
  used: '已用',
  available: '可用',
  size: '大小',
  filesystem: '文件系统',
  mountedOn: '挂载点',
  root: '根目录',
  entries: '条目',
  steps: '步骤',
  rows: '行',
  items: '项目',
  list: '列表',
  records: '记录',
  preview: '预览',
  logPath: '日志路径',
  linesAnalyzed: '分析行数',
  errorCount: '错误数',
  rootCause: '根因',
  recommendation: '建议',
  lineCount: '行数',
  anomalyLineCount: '异常行数',
  maxAnomalyScore: '最大异常分',
  sampleLine: '样例行',
  severity: '级别',
  dominantSeverity: '主级别',
  anomalyScore: '异常分',
  line: '日志行',
  logTime: '日志时间',
  minEpochMs: '最早时间',
  maxEpochMs: '最晚时间',
  value: '值'
}

const ENUM_VALUE_LABELS = {
  SUCCESS: '成功',
  FAIL: '失败',
  FAILED: '失败',
  ERROR: '错误',
  WARN: '告警',
  WARNING: '告警',
  INFO: '信息',
  DEBUG: '调试',
  FATAL: '致命',
  CRITICAL: '严重',
  HEALTHY: '健康',
  UNHEALTHY: '异常',
  ALLOW: '允许',
  BLOCK: '拦截',
  REJECT: '拒绝',
  CONFIRM: '确认'
}

const METRIC_KEY_RE = /percent|usage|ratio|score|progress|level|risk/i
const BYTE_FIELD_KEYS = new Set(['available', 'size', 'used', 'free', 'total', 'capacity'])
const TABLE_ARRAY_KEYS = new Set(['preview', 'entries', 'steps', 'rows', 'items', 'list', 'records', 'anomalySamples', 'protectedSamples'])

export function fieldLabel(key) {
  if (key == null) return ''
  return FIELD_LABELS[key] || FIELD_LABELS[String(key)] || String(key)
}

function translateEnumValue(val) {
  if (val == null || val === '') return null
  return ENUM_VALUE_LABELS[String(val).toUpperCase()] ?? null
}

export function looksLikeJsonString(s) {
  const t = String(s).trim()
  return (t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))
}

export function coerceStructuredInput(val) {
  if (val == null) return null
  if (typeof val === 'string') {
    const t = val.trim()
    if (!t) return null
    if (looksLikeJsonString(t)) {
      try {
        return coerceStructuredInput(JSON.parse(t))
      } catch {
        return t
      }
    }
    return t
  }
  return val
}

export function payloadForStructuredView(raw) {
  if (raw == null) return null

  const val = coerceStructuredInput(raw)
  if (val && typeof val === 'object' && !Array.isArray(val)) {
    if (val.success !== undefined && (val.data !== undefined || val.needConfirm !== undefined)) {
      const normalized = normalizeMcpToolResponse(val)
      if (normalized.data != null && normalized.data !== '') return coerceStructuredInput(normalized.data)
      return {
        success: normalized.success,
        error: normalized.error,
        message: normalized.message,
        securityCode: normalized.securityCode,
        riskScore: normalized.riskScore,
        riskExplanation: normalized.riskExplanation,
        traceId: normalized.traceId,
        durationMs: normalized.duration
      }
    }
  }
  return val
}

function isPlainObject(v) {
  return v != null && typeof v === 'object' && !Array.isArray(v)
}

function parsePercentValue(val) {
  if (val == null || val === '') return null
  if (typeof val === 'number' && Number.isFinite(val)) {
    if (val >= 0 && val <= 1) return Math.round(val * 100)
    if (val >= 0 && val <= 100) return Math.round(val)
    if (val > 0 && val <= 10) return Math.round(val * 10)
    return null
  }
  const m = String(val).match(/(\d+(?:\.\d+)?)\s*%?/)
  if (!m) return null
  const n = Number.parseFloat(m[1])
  if (!Number.isFinite(n)) return null
  if (String(val).includes('%')) return Math.min(100, Math.round(n))
  if (n >= 0 && n <= 1) return Math.round(n * 100)
  if (n >= 0 && n <= 100) return Math.round(n)
  if (n > 0 && n <= 10) return Math.round(n * 10)
  return null
}

function metricStatus(percent, key) {
  const k = String(key || '').toLowerCase()
  if (k.includes('risk') || k.includes('error') || k.includes('anomaly')) {
    if (percent >= 70) return 'exception'
    if (percent >= 40) return 'warning'
    return 'success'
  }
  if (percent >= 90) return 'exception'
  if (percent >= 75) return 'warning'
  return 'success'
}

function formatBytesCompact(value) {
  if (value == null || value === '') return '-'
  if (typeof value === 'string' && /[a-zA-Z%]/.test(value)) return value
  const n = Number(value)
  if (!Number.isFinite(n)) return String(value)
  const units = ['B', 'KB', 'MB', 'GB', 'TB']
  let size = Math.max(0, n)
  let idx = 0
  while (size >= 1024 && idx < units.length - 1) {
    size /= 1024
    idx += 1
  }
  const digits = size >= 100 || idx === 0 ? 0 : size >= 10 ? 1 : 2
  return `${size.toFixed(digits)} ${units[idx]}`
}

export function formatBytes(value) {
  return formatBytesCompact(value)
}

function isByteField(key) {
  return BYTE_FIELD_KEYS.has(String(key || ''))
}

function formatScalar(val, key) {
  if (val == null) return '-'
  if (typeof val === 'boolean') return val ? '是' : '否'
  if (typeof val === 'number') {
    if (isByteField(key)) return formatBytesCompact(val)
    return Number.isInteger(val) ? String(val) : val.toFixed(2)
  }
  if (typeof val === 'string') {
    const translated = translateEnumValue(val)
    const s = translated ?? val
    if (isByteField(key) && !/[a-zA-Z%]/.test(s) && /^\d+(\.\d+)?$/.test(s)) return formatBytesCompact(Number(s))
    return s.length > 500 ? `${s.slice(0, 500)}…` : s
  }
  return String(val)
}

export function formatTableCell(prop, val) {
  if (val == null || val === '') return '-'
  if (typeof val === 'boolean') return val ? '是' : '否'
  if (typeof val === 'object') return fieldLabel('value')
  if (prop === 'kb') return formatBytesCompact(Number(val) * 1024)
  if (BYTE_FIELD_KEYS.has(prop)) return formatBytesCompact(val)
  if (prop === 'unparsedTimeRatio' && typeof val === 'number') return `${(val * 100).toFixed(1)}%`
  if ((prop === 'minEpochMs' || prop === 'maxEpochMs') && typeof val === 'number') {
    const d = new Date(val)
    return Number.isNaN(d.getTime()) ? String(val) : d.toLocaleString('zh-CN', { hour12: false })
  }
  const translated = translateEnumValue(val)
  const s = formatScalar(translated ?? val, prop)
  return s.length > 120 ? `${s.slice(0, 120)}…` : s
}

function tagTypeForField(key, val) {
  const k = String(key).toLowerCase()
  if (typeof val === 'boolean') return val ? 'success' : 'info'
  if (k === 'success' || k === 'executionok' || k === 'passed') return val === true || val === 'true' ? 'success' : 'danger'
  if (k.includes('error') || k.includes('fail') || k === 'severity') return 'danger'
  if (k.includes('warn') || k === 'risklevel') return 'warning'
  if (k.includes('status') || k === 'mode') return 'info'
  return undefined
}

function inferTableColumns(rows) {
  const keys = new Set()
  for (const row of rows.slice(0, 20)) {
    if (!isPlainObject(row)) continue
    Object.keys(row).forEach((k) => keys.add(k))
  }
  const ordered = [...keys]
  const priority = [
    'templateId',
    'lineCount',
    'anomalyLineCount',
    'maxAnomalyScore',
    'dominantSeverity',
    'sampleLine',
    'severity',
    'anomalyScore',
    'logTime',
    'line',
    'path',
    'kb',
    'mountedOn',
    'usePercent',
    'used',
    'size',
    'available',
    'filesystem',
    'pid',
    'cpu',
    'mem',
    'command'
  ]
  ordered.sort((a, b) => {
    const ia = priority.indexOf(a)
    const ib = priority.indexOf(b)
    if (ia === -1 && ib === -1) return a.localeCompare(b)
    if (ia === -1) return 1
    if (ib === -1) return -1
    return ia - ib
  })
  return ordered.map((prop) => ({ prop, label: fieldLabel(prop), minWidth: prop === 'path' ? 200 : 100 }))
}

function formatRowForDisplay(row, columns) {
  if (!isPlainObject(row)) return row
  const out = { ...row }
  for (const col of columns) {
    const v = out[col.prop]
    if (v == null || v === '') continue
    if (isByteField(col.prop) && typeof v === 'number') {
      out[col.prop] = formatBytesCompact(v)
    }
  }
  return out
}

function buildTableSection(key, arr, depth) {
  if (!arr.length) return null
  if (arr.every(isPlainObject)) {
    const columns = inferTableColumns(arr)
    return {
      kind: 'table',
      title: fieldLabel(key),
      columns,
      rows: arr.slice(0, 50).map((row) => formatRowForDisplay(row, columns))
    }
  }
  if (arr.every((x) => typeof x !== 'object')) {
    return {
      kind: 'tags',
      title: fieldLabel(key),
      items: arr.slice(0, 40).map((x) => formatScalar(x))
    }
  }
  if (depth >= 3) {
    return { kind: 'text', title: fieldLabel(key), text: `（${arr.length} 项，层级过深已折叠）` }
  }
  return {
    kind: 'cards',
    title: fieldLabel(key),
    cards: arr.slice(0, 12).map((item, i) => ({
      title: `${fieldLabel(key)} #${i + 1}`,
      view: buildStructuredViewModel(item, depth + 1)
    }))
  }
}

export function buildStructuredViewModel(raw, depth = 0) {
  const val = payloadForStructuredView(raw)

  if (val == null || val === '') return { kind: 'empty' }

  if (typeof val === 'string' || typeof val === 'number' || typeof val === 'boolean') {
    return { kind: 'text', text: formatScalar(val) }
  }

  if (Array.isArray(val)) {
    if (!val.length) return { kind: 'empty' }
    const tableSec = buildTableSection('items', val, depth)
    if (tableSec) return tableSec
    return { kind: 'text', text: formatScalar(val) }
  }

  if (!isPlainObject(val)) return { kind: 'text', text: formatScalar(val) }

  const metrics = []
  const fields = []
  const sections = []

  for (const [key, v] of Object.entries(val)) {
    if (v == null || v === '') continue

    const pct = parsePercentValue(v)
    if (pct != null && (METRIC_KEY_RE.test(key) || key === 'riskScore' || String(v).includes('%'))) {
      metrics.push({
        key,
        label: fieldLabel(key),
        percent: Math.min(100, Math.max(0, pct)),
        status: metricStatus(pct, key),
        hint: key === 'riskScore' ? `${pct / 10}/10` : undefined
      })
      continue
    }

    if (Array.isArray(v) && (TABLE_ARRAY_KEYS.has(key) || v.every(isPlainObject))) {
      const sec = buildTableSection(key, v, depth)
      if (sec) sections.push(sec)
      continue
    }

    if (key === 'severityHistogram' && isPlainObject(v)) {
      const rows = Object.entries(v).map(([severity, lineCount]) => ({ severity, lineCount }))
      const sec = buildTableSection('severityHistogram', rows, depth)
      if (sec) sections.push(sec)
      continue
    }

    if (typeof v === 'object') {
      if (depth >= 3) {
        fields.push({ key, label: fieldLabel(key), value: '（已折叠）', tagType: 'info' })
        continue
      }
      sections.push({
        kind: 'nested',
        title: fieldLabel(key),
        view: buildStructuredViewModel(v, depth + 1)
      })
      continue
    }

    fields.push({
      key,
      label: fieldLabel(key),
      value: formatScalar(v, key),
      tagType: tagTypeForField(key, v)
    })
  }

  if (metrics.length || fields.length || sections.length) {
    return { kind: 'composite', metrics, fields, sections }
  }

  return { kind: 'empty' }
}

export function formatResultPreview(raw, maxLen = 140) {
  const vm = buildStructuredViewModel(raw)
  const parts = []

  const walk = (m) => {
    if (!m || parts.length > 6) return
    if (m.kind === 'text' && m.text) parts.push(m.text)
    if (m.kind === 'composite') {
      for (const f of m.fields || []) parts.push(`${f.label}: ${f.value}`)
      for (const met of m.metrics || []) parts.push(`${met.label}: ${met.percent}%`)
    }
    if (m.kind === 'table' && m.rows?.length) {
      parts.push(`${m.title || '列表'} ${m.rows.length} 行`)
    }
    if (m.kind === 'tags' && m.items?.length) {
      parts.push(`${m.title || '项目'}: ${m.items.slice(0, 3).join('、')}`)
    }
    for (const s of m.sections || []) walk(s.view || s)
  }

  walk(vm)
  const text = parts.filter(Boolean).join(' · ') || '—'
  return text.length > maxLen ? `${text.slice(0, maxLen)}…` : text
}

export function formatRiskDimensions(dimensions) {
  if (!dimensions || typeof dimensions !== 'object') return ''
  return Object.entries(dimensions)
    .map(([k, v]) => `• ${fieldLabel(k)}: ${formatScalar(v, k)}`)
    .join('\n')
}
