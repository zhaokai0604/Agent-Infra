/**
 * 统一解析 Axios / MCP 错误文案，附带 securityCode。
 */
export function formatApiError (error) {
  const data = error?.response?.data
  if (data && typeof data === 'object') {
    const msg = data.message || data.error || data.msg
    const code = data.securityCode || data.code
    if (msg && code && !String(msg).includes(String(code))) {
      return `${msg}（安全码：${code}）`
    }
    if (msg) return String(msg)
    if (code) return `请求失败（安全码：${code}）`
  }
  if (error?.message) return error.message
  return '请求失败'
}

/** 工作台 Markdown 错误块 */
export function formatErrorMarkdown (errorOrPayload) {
  const p = errorOrPayload?.response?.data || errorOrPayload
  if (!p || typeof p !== 'object') {
    return `\`\`\`\n${formatApiError(errorOrPayload)}\n\`\`\``
  }
  const lines = []
  if (p.error) lines.push(String(p.error))
  if (p.message && p.message !== p.error) lines.push(String(p.message))
  if (p.securityCode) lines.push(`securityCode: ${p.securityCode}`)
  if (p.riskScore != null) lines.push(`riskScore: ${p.riskScore}`)
  if (p.traceId) lines.push(`traceId: ${p.traceId}`)
  if (!lines.length) lines.push(formatApiError({ response: { data: p } }))
  return '```\n' + lines.join('\n') + '\n```'
}
