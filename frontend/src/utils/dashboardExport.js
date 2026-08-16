import { jsPDF } from 'jspdf'
import { rasterizePdfTextBlock } from './pdfTextRaster.js'

/**
 * 将多张图表 PNG 与摘要写入 A4 横向 PDF。
 */
export async function exportDashboardPdfDocument ({
  title,
  summaryLine,
  chartImages,
  filename
}) {
  const pdf = new jsPDF({ orientation: 'landscape', unit: 'pt', format: 'a4' })
  const pageW = pdf.internal.pageSize.getWidth()
  const pageH = pdf.internal.pageSize.getHeight()
  const margin = 36
  const contentW = pageW - margin * 2

  let headerBottom = margin
  const headerRaster = rasterizePdfTextBlock({
    title: title || '证据趋势看板',
    summaryLine,
    widthPt: contentW
  })
  if (headerRaster) {
    pdf.addImage(
      headerRaster.dataUrl,
      'PNG',
      margin,
      margin,
      headerRaster.widthPt,
      headerRaster.heightPt
    )
    headerBottom = margin + headerRaster.heightPt + 8
  }

  const cols = 2
  const chartW = (contentW - 16) / cols
  const chartH = (pageH - headerBottom - margin - 20) / 2 - 8

  const slots = chartImages.filter(Boolean)
  for (let i = 0; i < slots.length; i++) {
    const dataUrl = slots[i]
    const col = i % cols
    const row = Math.floor(i / cols)
    const x = margin + col * (chartW + 16)
    const y = headerBottom + row * (chartH + 12)
    try {
      pdf.addImage(dataUrl, 'PNG', x, y, chartW, chartH)
    } catch {
      /* 单图失败不阻断 */
    }
  }

  pdf.setFont('helvetica', 'normal')
  pdf.setFontSize(8)
  pdf.setTextColor(120, 120, 120)
  pdf.text(`Generated ${new Date().toLocaleString()}`, margin, pageH - 16)

  pdf.save(filename || `dashboard-report-${Date.now()}.pdf`)
}

export function chartToPngDataUrl (chart) {
  if (!chart) return null
  try {
    return chart.getDataURL({ type: 'png', pixelRatio: 2, backgroundColor: '#ffffff' })
  } catch {
    return null
  }
}
