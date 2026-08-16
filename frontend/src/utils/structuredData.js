/**
 * 将 API / 工具返回解析为可展示结构，避免页面堆砌 JSON.stringify。
 */

const FIELD_LABELS = {
  success: '执行结果',
  error: '错误',
  message: '消息',
  duration: '耗时(ms)',
  durationMs: '耗时(ms)',
  traceId: '链路ID',
  trace_id: '链路ID',
  needConfirm: '需二次确认',
  cancelled: '已取消',
  riskScore: '风险分',
  riskExplanation: '风险说明',
  securityCode: '安全码',
  toolName: '工具',
  mode: '模式',
  path: '路径',
  service: '服务',
  command: '命令',
  output: '输出',
  status: '状态',
  usePercent: '使用率',
  cpuUsagePercent: 'CPU',
  memUsagePercent: '内存',
  mountedOn: '挂载点',
  size: '容量',
  used: '已用',
  pid: 'PID',
  cpu: 'CPU%',
  mem: '内存%',
  logPath: '日志路径',
  linesAnalyzed: '分析行数',
  errorCount: '异常数',
  rootCause: '根因',
  recommendation: '建议'
}

const PERCENT_KEY_RE = /percent|usage|ratio|score|risk/i

export function unwrapStructuredValue(val) {
  if (val == null) return null
  if (typeof val === 'string') {
    const t = val.trim()
    if (!t) return ''
    if ((t.startsWith('{') && t.endsWith('}')) || (t.startsWith('[') && t.endsWith(']'))) {
      try {
        return JSON.parse(t)
      } catch {
        return val
      }
    }
    return val
  }
  return val
}

export function fieldLabel(key) {
  return FIELD_LABELS[key] || key
}

export function isPlainObject(v) {
  return v != null && typeof v === 'object' && !Array.isArray(v)
}

export function isArrayOfRecords(arr) {
  if (!Array.isArray(arr) || !arr.length) return false
  return arr.every((item) => isPlainObject(item))
}

/** @returns {number|null} 0–100 */
export function readPercentMetric(key, val) {
  if (val == null || val === '') return null
  if (typeof val === 'number' && Number.isFinite(val)) {
    if (!PERCENT_KEY_RE.test(String(key))) return null
    if (val >= 0 && val <= 100) return val
    if (val > 0 && val <= 10 && /risk/i.test(String(key))) return Math.round(val * 10)
    return null
  }
  if (typeof val === 'string' && val.endsWith('%')) {
    const n = parseInt(val.replace('%', ''), 10)
    return Number.isFinite(n) ? Math.min(100, Math.max(0, n)) : null
  }
  return null
}

export function progressStatus(percent, key = '') {
  if (/risk/i.test(String(key))) {
    if (percent >= 70) return 'exception'
    if (percent >= 40) return 'warning'
    return 'success'
  }
  if (percent >= 90) return 'exception'
  if (percent >= 75) return 'warning'
  return 'success'
}

export function boolTagType(val) {
  if (val === true) return 'success'
  if (val === false) return 'info'
  return 'info'
}

export function formatScalar(val) {
  if (val == null) return '—'
  if (typeof val === 'boolean') return val ? '是' : '否'
  if (typeof val === 'object') return ''
  return String(val)
}

/**
 * 从对象中提取适合进度条展示的指标。
 */
export function extractProgressMetrics(obj, depth = 0) {
  if (!isPlainObject(obj) || depth > 2) return []
  const out = []
  for (const [key, val] of Object.entries(obj)) {
    const pct = readPercentMetric(key, val)
    if (pct != null) {
      out.push({ key, label: fieldLabel(key), percent: pct })
      continue
    }
    if (isPlainObject(val)) {
      out.push(...extractProgressMetrics(val, depth + 1))
    }
  }
  return out.slice(0, 8)
}

/**
 * 扁平标量字段，供描述列表 / 表格使用。
 */
export function extractScalarRows(obj, depth = 0, prefix = '') {
  if (!isPlainObject(obj) || depth > 3) return []
  const rows = []
  for (const [key, val] of Object.entries(obj)) {
    if (val == null || val === '') continue
    const label = prefix ? `${prefix}.${fieldLabel(key)}` : fieldLabel(key)
    if (typeof val === 'boolean' || typeof val === 'number' || typeof val === 'string') {
      if (readPercentMetric(key, val) != null) continue
      rows.push({ key: prefix ? `${prefix}.${key}` : key, label, value: val, kind: typeof val })
    }
  }
  return rows
}

/**
 * 对象内嵌数组 → 独立表格区块。
 */
export function extractTableSections(obj) {
  if (!isPlainObject(obj)) return []
  const sections = []
  for (const [key, val] of Object.entries(obj)) {
    const unwrapped = unwrapStructuredValue(val)
    if (isArrayOfRecords(unwrapped)) {
      sections.push({
        key,
        title: fieldLabel(key),
        columns: buildColumnsFromRecords(unwrapped),
        rows: unwrapped
      })
    }
  }
  return sections
}

export function buildColumnsFromRecords(rows) {
  const keys = new Set()
  for (const row of rows.slice(0, 20)) {
    if (!isPlainObject(row)) continue
    Object.keys(row).forEach((k) => keys.add(k))
  }
  return [...keys].slice(0, 12).map((prop) => ({
    prop,
    label: fieldLabel(prop),
    minWidth: prop.length > 10 ? 140 : 100
  }))
}

/**
 * 嵌套对象 → 子卡片（非纯标量、非表格数组）。
 */
export function extractNestedCards(obj) {
  if (!isPlainObject(obj)) return []
  const cards = []
  for (const [key, val] of Object.entries(obj)) {
    const unwrapped = unwrapStructuredValue(val)
    if (unwrapped == null) continue
    if (typeof unwrapped === 'string' || typeof unwrapped === 'number' || typeof unwrapped === 'boolean') {
      continue
    }
    if (isArrayOfRecords(unwrapped)) continue
    if (isPlainObject(unwrapped)) {
      const scalars = extractScalarRows(unwrapped, 0)
      const tables = extractTableSections(unwrapped)
      const nested = extractNestedCards(unwrapped)
      if (scalars.length || tables.length || nested.length || Object.keys(unwrapped).length) {
        cards.push({ key, title: fieldLabel(key), value: unwrapped })
      }
    } else if (Array.isArray(unwrapped) && unwrapped.length) {
      cards.push({ key, title: fieldLabel(key), value: unwrapped })
    }
  }
  return cards
}

/** 列表预览：短句，不输出 JSON */
export function summarizeForPreview(val, maxLen = 140) {
  const parsed = unwrapStructuredValue(val)
  if (parsed == null) return '—'
  if (typeof parsed === 'string') {
    const t = parsed.trim()
    return t.length > maxLen ? t.slice(0, maxLen) + '…' : t || '—'
  }
  if (typeof parsed === 'number' || typeof parsed === 'boolean') {
    return String(parsed)
  }
  if (Array.isArray(parsed)) {
    if (!parsed.length) return '（空列表）'
    if (isArrayOfRecords(parsed)) {
      const first = parsed[0]
      const keys = Object.keys(first).slice(0, 2)
      const bits = keys.map((k) => `${fieldLabel(k)}=${formatScalar(first[k])}`).join(' ')
      return `共 ${parsed.length} 条 · ${bits}${parsed.length > 1 ? ' …' : ''}`
    }
    return parsed
      .slice(0, 3)
      .map((x) => formatScalar(x))
      .join('；') + (parsed.length > 3 ? ' …' : '')
  }
  if (isPlainObject(parsed)) {
    const bits = []
    for (const [k, v] of Object.entries(parsed)) {
      if (bits.length >= 4) break
      if (v == null || typeof v === 'object') continue
      bits.push(`${fieldLabel(k)}=${formatScalar(v)}`)
    }
    const line = bits.join(' · ')
    return line.length > maxLen ? line.slice(0, maxLen) + '…' : line || '（对象）'
  }
  return String(parsed).slice(0, maxLen)
}

/** 风险维度对象 → 标签行文案 */
export function formatRiskDimensions(dims) {
  const parsed = unwrapStructuredValue(dims)
  if (!parsed || typeof parsed !== 'object') return []
  return Object.entries(parsed).map(([k, v]) => ({
    key: k,
    label: fieldLabel(k),
    value: formatScalar(v)
  }))
}
