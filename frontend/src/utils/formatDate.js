/** 本地日历日 YYYY-MM-DD（避免 toISOString 时区偏移） */
export function formatLocalDateKey(date) {
  const d = date instanceof Date ? date : new Date(date)
  const y = d.getFullYear()
  const m = String(d.getMonth() + 1).padStart(2, '0')
  const day = String(d.getDate()).padStart(2, '0')
  return `${y}-${m}-${day}`
}

/** 展示 createTime：兼容 Jackson LocalDateTime 数组 / ISO / 时间戳 / 普通字符串 */
export function formatDateTime(val) {
  if (val == null || val === '') return '--'
  if (Array.isArray(val)) {
    const [y, m, d, h = 0, min = 0, s = 0] = val
    const pad = (n) => String(n).padStart(2, '0')
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s || 0)}`
  }
  if (typeof val === 'number' && Number.isFinite(val)) {
    const date = new Date(val)
    if (!Number.isNaN(date.getTime())) {
      return date.toLocaleString('zh-CN', { hour12: false })
    }
  }
  if (val instanceof Date && !Number.isNaN(val.getTime())) {
    return val.toLocaleString('zh-CN', { hour12: false })
  }
  if (typeof val === 'string') {
    if (val.includes('T')) {
      return val.replace('T', ' ').substring(0, 19)
    }
    const parsed = new Date(val.replace(/-/g, '/'))
    if (!Number.isNaN(parsed.getTime())) {
      return parsed.toLocaleString('zh-CN', { hour12: false })
    }
    return val
  }
  return String(val)
}
