import MarkdownIt from 'markdown-it'

const md = new MarkdownIt({
  html: false,
  linkify: true,
  breaks: true,
  typographer: false
})

const RE_TABLE_SEPARATOR = /^\s*\|?[\s:-]+\|[\s|:-]*$/
const RE_BLOCK_HEADING = /(^|\n)#{1,6}\s/
const RE_HEADING_INLINE = /```/
const RE_HEADING_PREFIX = /^#{1,6}\s+/
const RE_HEADING_ANY = /\s*#{1,6}\s+/g
const RE_BULLET_DOT = /^[\t ]*[•·▪◦]\s+/gm
const RE_BULLET_CN = /^[\t ]*([一二三四五六七八九十]+)[、．]\s+/gm
const RE_HEADING_NL = /([^\n])\n(#{1,6}\s)/g
const RE_HEADING_INLINE_SPLIT = /([^\n#])(#{1,6}\s)/g
const RE_SEPARATOR_CELL = /^[-—_\s/|]+$/
const RE_SEPARATOR_CELL_ALT = /^[-—]{2,}(\s*\/\s*[-—]{2,})?$/
const RE_HEADING_IN_LABEL = /^#{1,6}\s|##|###/
const RE_CMD_LINE = /^(powershell|pwsh|netsh|Get-|docker|systemctl|kubectl|curl|ssh|chkdsk|winmgmt)\b/i
const RE_CODE_BLOCK = /<pre><code(?: class="language-([^"]*)")?>([\s\S]*?)<\/code><\/pre>/g
const RE_DOUBLE_PIPE = /\|\|+/

function escapeHtml(s) {
  return String(s)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
}

function decodeHtmlEntities(s) {
  return String(s)
    .replace(/&gt;/gi, '>')
    .replace(/&lt;/gi, '<')
    .replace(/&quot;/gi, '"')
    .replace(/&#39;|&apos;/gi, "'")
    .replace(/&amp;/gi, '&')
}

function escapeRegExp(s) {
  return String(s).replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
}

function tableMarker(idx) {
  return `\uE000T${idx}\uE001`
}

function isTableSeparator(line) {
  return RE_TABLE_SEPARATOR.test(line.trim())
}

function isTableRow(line) {
  const t = line.trim()
  if (!t || t.startsWith('```')) return false
  return t.includes('|') && (t.match(/\|/g) || []).length >= 2
}

function parseCells(line) {
  let row = line.trim()
  if (row.startsWith('|')) row = row.slice(1)
  if (row.endsWith('|')) row = row.slice(0, -1)
  return row.split('|').map(c => c.trim())
}

function isSeparatorCell(text) {
  const t = String(text ?? '').trim()
  return !t || RE_SEPARATOR_CELL.test(t) || RE_SEPARATOR_CELL_ALT.test(t)
}

function isSeparatorRow(cells) {
  return !cells?.length || cells.every(isSeparatorCell)
}

function hasBlockMarkdown(text) {
  const t = String(text ?? '')
  return RE_BLOCK_HEADING.test(t) || RE_HEADING_INLINE.test(t)
}

function renderCellHtml(text) {
  const raw = String(text ?? '').trim()
  if (!raw) return ''
  if (isSeparatorCell(raw)) return '<span class="prose-cell-muted">—</span>'

  let cell = raw
    .replace(RE_HEADING_PREFIX, '')
    .replace(RE_HEADING_ANY, ' ')

  try {
    return renderSeverityBadges(md.renderInline(cell))
  } catch {
    return escapeHtml(cell)
  }
}

function normalizeSectionBreaks(text) {
  return text
    .replace(RE_HEADING_INLINE_SPLIT, '$1\n\n$2')
    .replace(RE_HEADING_NL, '$1\n\n$2')
    .replace(/\n{3,}/g, '\n\n')
}

function normalizeHeadings(text) {
  return normalizeSectionBreaks(text)
}

function normalizeBullets(text) {
  return text
    .replace(RE_BULLET_DOT, '- ')
    .replace(RE_BULLET_CN, '- ')
}

function formatAsMarkdownTable(rows, headers = ['指标', '数值']) {
  const lines = [
    `| ${headers[0]} | ${headers[1]} |`,
    '|------|------|'
  ]
  for (const [k, v] of rows) {
    lines.push(`| ${k} | ${v} |`)
  }
  return lines.join('\n')
}

/** 解析 ||标签||值||标签||值|| 或 |标签|值| 交替格式 */
function parsePipeTableRows(line) {
  const t = String(line ?? '').trim()
  if (!t || t.startsWith('```')) return null
  if (!RE_DOUBLE_PIPE.test(t) && !(t.startsWith('|') && (t.match(/\|/g) || []).length >= 3)) {
    return null
  }

  const chunks = t.replace(/^\|+/, '').replace(/\|+$/, '').split(RE_DOUBLE_PIPE).map(s => s.trim()).filter(Boolean)
  if (chunks.length < 2) return null

  const rows = []
  const allHaveInnerPipe = chunks.every(c => c.includes('|'))

  if (allHaveInnerPipe) {
    for (const chunk of chunks) {
      const parts = chunk.split('|').map(c => c.trim()).filter(Boolean)
      if (parts.length < 2 || isSeparatorCell(parts[0])) continue
      const label = parts[0]
      const value = parts.slice(1).join(' / ')
      if (RE_HEADING_IN_LABEL.test(label) || label.length > 48 || value.length > 220) continue
      rows.push([label, value])
    }
  } else {
    for (let i = 0; i + 1 < chunks.length; i += 2) {
      const label = chunks[i]
      const value = chunks[i + 1]
      if (!label || !value || isSeparatorCell(label)) continue
      if (RE_HEADING_IN_LABEL.test(label) || label.length > 48 || value.length > 220) continue
      rows.push([label, value])
    }
  }

  return rows.length ? rows : null
}

function splitHeadingAndPipeTable(line) {
  const t = line.trim()
  const m = t.match(/^(#{1,6}\s+[^|#\n]+?)(\s+(?:\|\|.+|\|.+\|.+))$/)
  if (!m) return null
  const rows = parsePipeTableRows(m[2])
  if (!rows?.length) return null
  return { heading: m[1].trim(), table: formatAsMarkdownTable(rows) }
}

function expandInlinePipeTables(text) {
  return text.split('\n').flatMap(line => {
    const split = splitHeadingAndPipeTable(line)
    if (split) {
      return [split.heading, '', split.table]
    }

    const rows = parsePipeTableRows(line)
    if (rows?.length) {
      return [formatAsMarkdownTable(rows)]
    }
    return [line]
  }).join('\n')
}

function buildTableHtml(headerCells, bodyRows) {
  let html = '<div class="prose-table-wrap"><table><thead><tr>'
  for (const cell of headerCells) html += `<th>${renderCellHtml(cell)}</th>`
  html += '</tr></thead><tbody>'
  for (const row of bodyRows) {
    html += '<tr>'
    for (let c = 0; c < headerCells.length; c++) html += `<td>${renderCellHtml(row[c] ?? '')}</td>`
    html += '</tr>'
  }
  return html + '</tbody></table></div>'
}

function isValidTableBlock(headerCells, bodyRows) {
  if (!headerCells.length || headerCells.some(c => c.length > 60 && hasBlockMarkdown(c))) return false
  if (headerCells.some(c => hasBlockMarkdown(c))) return false
  const rows = bodyRows.filter(r => !isSeparatorRow(r))
  if (!rows.length) return false
  if (rows.some(r => r[0]?.length > 100 && hasBlockMarkdown(r[0]))) return false
  return true
}

function extractTables(text) {
  const lines = text.split('\n')
  const out = []
  const tables = []
  let i = 0
  while (i < lines.length) {
    if (isTableRow(lines[i]) && i + 1 < lines.length && isTableSeparator(lines[i + 1])) {
      const headerCells = parseCells(lines[i])
      i += 2
      const bodyRows = []
      while (i < lines.length && isTableRow(lines[i])) {
        const cells = parseCells(lines[i])
        if (!isSeparatorRow(cells)) bodyRows.push(cells)
        i++
      }
      if (isValidTableBlock(headerCells, bodyRows)) {
        tables.push(buildTableHtml(headerCells, bodyRows))
        out.push('', tableMarker(tables.length - 1), '')
      } else {
        out.push('')
        for (const row of [headerCells, ...bodyRows]) {
          if (isSeparatorRow(row)) continue
          const [k, ...rest] = row
          const v = rest.join(' / ').trim()
          if (k && v && !isSeparatorCell(k)) out.push(`- **${k.replace(RE_HEADING_PREFIX, '')}**：${v}`)
          else if (k && !isSeparatorCell(k)) out.push(`- ${k}`)
        }
        out.push('')
      }
    } else {
      out.push(lines[i])
      i++
    }
  }
  return { text: out.join('\n'), tables }
}

function injectTables(html, tables) {
  if (!tables.length) return html
  let result = html
  tables.forEach((block, idx) => {
    const token = escapeRegExp(tableMarker(idx))
    result = result.replace(new RegExp(`<p>\\s*${token}\\s*</p>`, 'g'), block)
    result = result.replace(new RegExp(token, 'g'), block)
  })
  result = result.replace(/<p>\s*<strong>TABLE_(\d+)<\/strong>\s*<\/p>/g, (_, n) => tables[Number(n)] ?? '')
  result = result.replace(/<strong>TABLE_(\d+)<\/strong>/g, (_, n) => tables[Number(n)] ?? '')
  return result
}

function fenceCommands(text) {
  return text.split('\n').flatMap(line => {
    const t = line.trim()
    if (t && RE_CMD_LINE.test(t) && !t.startsWith('```') && !t.startsWith('|') && !t.startsWith('-')) {
      return ['', '```powershell', t, '```', '']
    }
    return [line]
  }).join('\n')
}

function wrapCodeBlocks(html) {
  return html.replace(RE_CODE_BLOCK, (_, lang, code) => {
    const label = lang ? escapeHtml(lang) : 'code'
    return (
      '<div class="prose-code-block">' +
      `<div class="prose-code-head"><span>${label}</span>` +
      '<button type="button" class="prose-code-copy">复制</button></div>' +
      `<pre><code${lang ? ` class="language-${lang}"` : ''}>${code}</code></pre></div>`
    )
  })
}

/** 严重程度 / 状态 → 徽章 HTML */
function renderSeverityBadges(text) {
  let s = String(text ?? '')
  s = s.replace(/\[(HIGH|WARN|WARNING|MEDIUM|INFO|CRITICAL|FATAL)\]/gi, (_, level) => {
    const lv = level.toUpperCase()
    const cls =
      lv === 'HIGH' || lv === 'CRITICAL' || lv === 'FATAL' ? 'prose-badge prose-badge--danger'
        : lv === 'WARN' || lv === 'WARNING' || lv === 'MEDIUM' ? 'prose-badge prose-badge--warn'
          : 'prose-badge prose-badge--info'
    return `<span class="${cls}">${lv === 'WARNING' ? 'WARN' : lv}</span>`
  })
  s = s.replace(/🟢/g, '<span class="prose-badge prose-badge--ok">正常</span>')
  s = s.replace(/🟡/g, '<span class="prose-badge prose-badge--warn">注意</span>')
  s = s.replace(/🔴/g, '<span class="prose-badge prose-badge--danger">严重</span>')
  s = s.replace(/\s*\/\s*(正常|健康|良好)\b/g, ' / <span class="prose-badge prose-badge--ok">$1</span>')
  s = s.replace(/\s*\/\s*(注意|偏高|预警|中等)\b/g, ' / <span class="prose-badge prose-badge--warn">$1</span>')
  s = s.replace(/\s*\/\s*(严重|危急|异常|偏高危)\b/g, ' / <span class="prose-badge prose-badge--danger">$1</span>')
  s = s.replace(/(?<!prose-metric">)(\d+(?:\.\d+)?)\s*%/g, '<span class="prose-metric">$1%</span>')
  return s
}

function stabilizePartialMarkdown(text) {
  const lines = String(text ?? '').split('\n')
  if (lines.length <= 1) return text

  let trimmed = [...lines]
  while (trimmed.length > 1) {
    const last = trimmed[trimmed.length - 1]
    const t = last.trim()
    if (!t) {
      trimmed.pop()
      continue
    }
    if (/^#{1,6}\s*$/.test(t)) {
      trimmed.pop()
      continue
    }
    if (t.startsWith('|') && !isTableSeparator(t) && !t.endsWith('|')) {
      trimmed.pop()
      continue
    }
    if (/^\|\s*[-—:\s|]+\s*$/.test(t) && trimmed.length > 1 && !isTableRow(trimmed[trimmed.length - 2])) {
      trimmed.pop()
      continue
    }
    if (/^\*\*[^*\n]{0,40}$/.test(t) || /^`[^`\n]{0,80}$/.test(t)) {
      trimmed.pop()
      continue
    }
    break
  }
  return trimmed.join('\n')
}

function sectionVariant(title) {
  const t = String(title ?? '').trim()
  if (/结论|概述|总结|summary|conclusion/i.test(t)) return 'conclusion'
  if (/诊断|分析|原因|diagnosis|analysis|根因/i.test(t)) return 'analysis'
  if (/建议|行动|处置|remediation|action|下一步/i.test(t)) return 'action'
  if (/事实|数据|指标|metrics|facts|现状/i.test(t)) return 'facts'
  return 'default'
}

function wrapSectionBlocks(html) {
  const matches = [...html.matchAll(/<h([12]) class="prose-section-title">([\s\S]*?)<\/h\1>/gi)]
  if (!matches.length) return html

  let out = html.slice(0, matches[0].index)
  for (let i = 0; i < matches.length; i++) {
    const m = matches[i]
    const titlePlain = m[2].replace(/<[^>]+>/g, '').trim()
    const variant = sectionVariant(titlePlain)
    const bodyStart = m.index + m[0].length
    const bodyEnd = i + 1 < matches.length ? matches[i + 1].index : html.length
    const body = html.slice(bodyStart, bodyEnd).trim()

    out += `<section class="prose-section-card prose-section-card--${variant}">`
    out += `<header class="prose-section-card__head">${m[0]}</header>`
    if (body) out += `<div class="prose-section-card__body">${body}</div>`
    out += '</section>'
  }
  return out
}

function findingSeverityClass(level) {
  const lv = String(level ?? '').toUpperCase()
  if (lv === 'HIGH' || lv === 'CRITICAL' || lv === 'FATAL') return 'prose-finding prose-finding--danger'
  if (lv === 'WARN' || lv === 'WARNING' || lv === 'MEDIUM') return 'prose-finding prose-finding--warn'
  return 'prose-finding prose-finding--info'
}

function enhanceProseHtml(html) {
  if (!html) return ''

  let out = html

  // 进度 / 脚注 / 警告 blockquote
  out = out.replace(/<blockquote>\s*<p>([\s\S]*?)<\/p>\s*<\/blockquote>/gi, (_, inner) => {
    const plain = inner.replace(/<[^>]+>/g, '').trim().replace(/^>\s*/, '')
    if (/⚠|写操作未落地|preview-only|未落地/i.test(plain)) {
      return `<div class="prose-warning-callout">${renderSeverityBadges(inner)}</div>`
    }
    if (/^(正在|Collecting|Starting)/i.test(plain)) {
      return `<div class="prose-status-hint">${escapeHtml(plain)}</div>`
    }
    if (/数据依据|Data Basis/i.test(plain)) {
      return `<div class="prose-footnote">${renderSeverityBadges(inner)}</div>`
    }
    return `<blockquote class="prose-callout"><p>${inner}</p></blockquote>`
  })

  out = out.replace(/<h1>/g, '<h1 class="prose-section-title">')
  out = out.replace(/<h2>/g, '<h2 class="prose-section-title">')
  out = out.replace(/<h3>/g, '<h3 class="prose-subsection-title">')

  out = wrapSectionBlocks(out)

  // 首段引导语
  let leadApplied = false
  out = out.replace(/(<p>)([^<]{6,280})(<\/p>)/g, (m, _open, text, _close) => {
    if (leadApplied) return m
    if (text.includes('prose-badge') || /^[>⚠]/.test(text.trim()) || /^(正在|Collecting)/i.test(text.trim())) {
      return m
    }
    leadApplied = true
    return `<p class="prose-lead">${text}</p>`
  })

  // 键值列表项
  out = out.replace(
    /<li>(?:\s*<p>)?<strong>([^<]+)<\/strong>[：:]\s*([\s\S]*?)(?:<\/p>)?\s*<\/li>/g,
    (_, label, value) =>
      `<li class="prose-kv-item"><span class="prose-kv-label">${label}</span><span class="prose-kv-sep">：</span><span class="prose-kv-value">${renderSeverityBadges(value)}</span></li>`
  )

  // 告警发现项（[HIGH] / [WARN] 列表）
  out = out.replace(
    /<li(?![^>]*class=")([^>]*)>([\s\S]*?\[(HIGH|WARN|WARNING|MEDIUM|INFO|CRITICAL|FATAL)\][\s\S]*?)<\/li>/gi,
    (_, attrs, item, level) =>
      `<li class="${findingSeverityClass(level)}"${attrs}>${renderSeverityBadges(item)}</li>`
  )

  // 有序步骤列表
  out = out.replace(/<ol>/g, '<ol class="prose-steps">')

  // 列表项 severity
  out = out.replace(/(<li)(?![^>]*class=")([^>]*)>([\s\S]*?)<\/li>/g, (_, open, attrs, item) => {
    if (/\[(HIGH|WARN|INFO|MEDIUM|CRITICAL)\]|🟢|🟡|🔴/.test(item)) {
      return `${open} class="prose-finding prose-finding--info"${attrs}>${renderSeverityBadges(item)}</li>`
    }
    return `${open}${attrs}>${item}</li>`
  })

  // 段落内 severity
  out = out.replace(/<p>([^<]*\[(?:HIGH|WARN|INFO|MEDIUM|CRITICAL)\][^<]*)<\/p>/gi, (_, inner) => {
    return `<p>${renderSeverityBadges(inner)}</p>`
  })

  out = out.replace(/<p>([^<]*(?:[🟢🟡🔴]|\/\s*(?:正常|注意|偏高|严重))[^<]*)<\/p>/g, (_, inner) => {
    if (inner.includes('prose-badge')) return `<p>${inner}</p>`
    return `<p>${renderSeverityBadges(inner)}</p>`
  })

  return out
}

function stripDecorativeSeparators(text) {
  return text.split('\n').filter(line => {
    const t = line.trim()
    return !/^(-{3,}|\*{3,}|={3,})$/.test(t)
  }).join('\n')
}

function normalizeTableSeparators(text) {
  return text.replace(/^\|(\s*[-—:]+\s*\|)+$/gm, line => {
    const cols = (line.match(/\|/g) || []).length - 1
    if (cols < 1) return line
    return '|' + '------|'.repeat(cols)
  })
}

function preprocess(raw, options = {}) {
  let text = decodeHtmlEntities(raw).replace(/\r\n/g, '\n').trim()
  if (options.streaming) {
    text = stabilizePartialMarkdown(text)
  }
  text = stripDecorativeSeparators(text)
  text = normalizeTableSeparators(text)
  text = normalizeBullets(text)
  text = normalizeHeadings(text)
  text = expandInlinePipeTables(text)
  text = fenceCommands(text)
  return text
}

export function renderAssistantMarkdown(raw, options = {}) {
  if (raw == null || raw === '') return ''
  const processed = preprocess(raw, options)
  const { text, tables } = extractTables(processed)
  let html = md.render(text)
  html = injectTables(html, tables)
  html = wrapCodeBlocks(html)
  return enhanceProseHtml(html)
}

export function bindProseCopyButtons(rootEl) {
  if (!rootEl) return
  rootEl.querySelectorAll('.prose-code-copy').forEach(btn => {
    if (btn.dataset.bound) return
    btn.dataset.bound = '1'
    btn.addEventListener('click', async () => {
      const text = btn.closest('.prose-code-block')?.querySelector('pre')?.textContent || ''
      try {
        await navigator.clipboard.writeText(text)
        const prev = btn.textContent
        btn.textContent = '已复制'
        setTimeout(() => { btn.textContent = prev }, 1200)
      } catch {
        btn.textContent = '失败'
      }
    })
  })
}
