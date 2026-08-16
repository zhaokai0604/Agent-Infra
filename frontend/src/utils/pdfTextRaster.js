const FONT_STACK = '"Microsoft YaHei", "PingFang SC", "Noto Sans SC", "Helvetica Neue", sans-serif'

/**
 * 将含中文的标题/摘要栅格化为 PNG，供 jsPDF 嵌入（内置 Helvetica 不支持 CJK）。
 * @returns {{ dataUrl: string, widthPt: number, heightPt: number } | null}
 */
export function rasterizePdfTextBlock ({ title, summaryLine, widthPt = 720 }) {
  const lines = []
  if (title) lines.push({ text: title, bold: true, size: 16 })
  if (summaryLine) lines.push({ text: summaryLine, bold: false, size: 10 })
  if (!lines.length) return null

  const scale = 2
  const padX = 0
  const lineGap = 6
  const canvas = document.createElement('canvas')
  const ctx = canvas.getContext('2d')
  if (!ctx) return null

  ctx.font = `600 16px ${FONT_STACK}`
  let totalH = 8
  for (const line of lines) {
    const size = line.size
    totalH += size + lineGap
  }

  canvas.width = Math.ceil(widthPt * scale)
  canvas.height = Math.ceil(totalH * scale)
  ctx.scale(scale, scale)
  ctx.fillStyle = '#ffffff'
  ctx.fillRect(0, 0, widthPt, totalH)

  let y = 4
  for (const line of lines) {
    ctx.fillStyle = '#303133'
    ctx.font = `${line.bold ? 600 : 400} ${line.size}px ${FONT_STACK}`
    ctx.fillText(line.text, padX, y + line.size)
    y += line.size + lineGap
  }

  return {
    dataUrl: canvas.toDataURL('image/png'),
    widthPt,
    heightPt: totalH
  }
}
