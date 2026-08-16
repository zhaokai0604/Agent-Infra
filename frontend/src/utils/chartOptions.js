/** 稀疏类目轴柱状图：避免只有 1～2 个点时柱子撑满整屏 */
export function sparseBarLayout(count) {
  const n = Math.max(1, Number(count) || 1)
  if (n === 1) {
    return { barMaxWidth: 36, barCategoryGap: '72%', barGap: '24%' }
  }
  if (n <= 2) {
    return { barMaxWidth: 40, barCategoryGap: '58%', barGap: '28%' }
  }
  if (n <= 5) {
    return { barMaxWidth: 28, barCategoryGap: '40%', barGap: '18%' }
  }
  return { barMaxWidth: 24, barCategoryGap: '28%', barGap: '12%' }
}

export function formatChartDayLabel(day) {
  const s = String(day || '')
  return s.length >= 10 ? s.slice(5) : s
}

export function chartEmptyGraphic(text = '暂无数据') {
  return {
    type: 'text',
    left: 'center',
    top: 'middle',
    style: {
      text,
      fill: '#94a3b8',
      fontSize: 13
    }
  }
}

export function pathBasename(path) {
  const p = String(path || '').replace(/\\/g, '/').replace(/\/+$/, '')
  if (!p) return '—'
  const parts = p.split('/').filter(Boolean)
  return parts[parts.length - 1] || p
}

export function shortenPathMiddle(path, maxLen = 36) {
  const p = String(path || '')
  if (p.length <= maxLen) return p
  const head = Math.ceil((maxLen - 1) / 2)
  const tail = Math.floor((maxLen - 1) / 2)
  return `${p.slice(0, head)}…${p.slice(-tail)}`
}

export function formatMiBLabel(v) {
  const n = Number(v)
  if (!Number.isFinite(n) || n <= 0) return '0 MiB'
  if (n >= 1024) return `${(n / 1024).toFixed(1)} GiB`
  return `${n.toFixed(n >= 100 ? 0 : 1)} MiB`
}
