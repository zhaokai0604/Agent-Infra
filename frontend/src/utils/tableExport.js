import html2canvas from 'html2canvas'
import { jsPDF } from 'jspdf'
import { rasterizePdfTextBlock } from './pdfTextRaster.js'

function unlockTableScroll (rootEl) {
  if (!rootEl) return () => {}
  const bodyWrapper = rootEl.querySelector('.el-table__body-wrapper')
  const saved = {
    height: bodyWrapper?.style.height ?? '',
    maxHeight: bodyWrapper?.style.maxHeight ?? ''
  }
  if (bodyWrapper) {
    bodyWrapper.style.height = 'auto'
    bodyWrapper.style.maxHeight = 'none'
  }
  return () => {
    if (bodyWrapper) {
      bodyWrapper.style.height = saved.height
      bodyWrapper.style.maxHeight = saved.maxHeight
    }
  }
}

/** 导出前展开 max-height / overflow 区域（如 CoT 结果摘要卡片） */
function unlockScrollContainers (rootEl) {
  if (!rootEl) return () => {}
  const restores = []
  rootEl.querySelectorAll('.export-scroll-unlock').forEach((el) => {
    const saved = {
      maxHeight: el.style.maxHeight,
      overflowY: el.style.overflowY,
      height: el.style.height
    }
    el.style.maxHeight = 'none'
    el.style.overflowY = 'visible'
    el.style.height = 'auto'
    restores.push(() => {
      el.style.maxHeight = saved.maxHeight
      el.style.overflowY = saved.overflowY
      el.style.height = saved.height
    })
  })
  return () => restores.forEach((fn) => fn())
}

function unlockExportArea (rootEl) {
  const fns = [unlockTableScroll(rootEl), unlockScrollContainers(rootEl)]
  return () => fns.forEach((fn) => fn())
}

async function captureElement (element) {
  if (!element) {
    throw new Error('无导出区域')
  }
  const restore = unlockExportArea(element)
  await new Promise((r) => requestAnimationFrame(() => requestAnimationFrame(r)))
  try {
    return await html2canvas(element, {
      scale: 2,
      backgroundColor: '#ffffff',
      logging: false,
      useCORS: true
    })
  } finally {
    restore()
  }
}

/**
 * 将表格区域导出为 PNG（与页面所见一致，含中文）。
 */
export async function exportTableAsPng (element, filename) {
  const canvas = await captureElement(element)
  const link = document.createElement('a')
  link.download = filename || `table-export-${Date.now()}.png`
  link.href = canvas.toDataURL('image/png')
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
}

/**
 * 将表格区域导出为多页 PDF（横向 A4）。
 */
export async function exportTableAsPdf ({ element, title, summaryLine, filename }) {
  const canvas = await captureElement(element)
  const pdf = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const margin = 36
  const contentW = pageW - margin * 2
  const headerRaster = rasterizePdfTextBlock({ title, summaryLine, widthPt: contentW })
  const headerBlock = headerRaster ? headerRaster.heightPt + 12 : margin
  const pageContentH = pageH - margin - headerBlock

  const scale = contentW / canvas.width
  const totalScaledH = canvas.height * scale
  let srcY = 0
  let pageIndex = 0

  const drawHeader = () => {
    if (headerRaster) {
      pdf.addImage(
        headerRaster.dataUrl,
        'PNG',
        margin,
        margin,
        headerRaster.widthPt,
        headerRaster.heightPt
      )
    }
  }

  while (srcY < totalScaledH - 0.5) {
    if (pageIndex > 0) {
      pdf.addPage()
    }
    drawHeader()

    const sliceScaledH = Math.min(pageContentH, totalScaledH - srcY)
    const sliceSrcH = sliceScaledH / scale
    const sliceCanvas = document.createElement('canvas')
    sliceCanvas.width = canvas.width
    sliceCanvas.height = Math.ceil(sliceSrcH)
    const ctx = sliceCanvas.getContext('2d')
    ctx.fillStyle = '#ffffff'
    ctx.fillRect(0, 0, sliceCanvas.width, sliceCanvas.height)
    ctx.drawImage(
      canvas,
      0,
      srcY / scale,
      canvas.width,
      sliceSrcH,
      0,
      0,
      canvas.width,
      sliceSrcH
    )

    const imgData = sliceCanvas.toDataURL('image/png')
    pdf.addImage(imgData, 'PNG', margin, headerBlock, contentW, sliceScaledH)
    srcY += sliceScaledH
    pageIndex += 1
  }

  pdf.setFontSize(8)
  pdf.setTextColor(120, 120, 120)
  pdf.text(`Generated ${new Date().toLocaleString()}`, margin, pageH - 14)

  pdf.save(filename || `table-export-${Date.now()}.pdf`)
}

export function buildTableSummaryLine (rowCount, extra = '') {
  const base = `共 ${rowCount} 条记录 · 导出时间 ${new Date().toLocaleString()}`
  return extra ? `${base} · ${extra}` : base
}

function escapeCsvCell (value) {
  if (value == null) return ''
  const text = typeof value === 'object' ? JSON.stringify(value) : String(value)
  if (/[",\n\r]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`
  }
  return text
}

/**
 * 将行数据导出为 CSV（UTF-8 BOM，Excel 友好）。
 * @param {{ rows: object[], columns: { key: string, label: string }[], filename?: string }} opts
 */
export function exportRowsAsCsv ({ rows, columns, filename }) {
  if (!Array.isArray(rows) || !rows.length) {
    throw new Error('暂无数据可导出')
  }
  if (!Array.isArray(columns) || !columns.length) {
    throw new Error('未指定导出列')
  }
  const header = columns.map((c) => escapeCsvCell(c.label)).join(',')
  const body = rows.map((row) =>
    columns.map((c) => escapeCsvCell(row[c.key])).join(',')
  )
  const csv = `\uFEFF${[header, ...body].join('\n')}`
  const blob = new Blob([csv], { type: 'text/csv;charset=utf-8;' })
  const link = document.createElement('a')
  link.href = URL.createObjectURL(blob)
  link.download = filename || `export-${Date.now()}.csv`
  link.style.display = 'none'
  document.body.appendChild(link)
  link.click()
  document.body.removeChild(link)
  URL.revokeObjectURL(link.href)
}
