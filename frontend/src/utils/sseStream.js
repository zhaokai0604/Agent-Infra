/**
 * 解析 Spring SseEmitter / 标准 text/event-stream，只拼接 data 行中的正文（去掉 data: 前缀）。
 * 忽略注释行、心跳与 [DONE]。
 */
export function appendSsePayloadFromChunk(buffer, chunkText, onPayload) {
  const buf = String(buffer || '') + String(chunkText || '')
  const normalized = buf.replace(/\r\n/g, '\n').replace(/\r/g, '\n')
  const events = normalized.split('\n\n')
  const rest = events.pop()
  for (const eventText of events) {
    emitSseEventPayload(eventText, onPayload)
  }
  return rest ?? ''
}

export function flushSseBuffer(buffer, onPayload) {
  emitSseEventPayload(buffer, onPayload)
  return ''
}

function emitSseEventPayload(eventText, onPayload) {
  if (!eventText || !String(eventText).trim()) return
  const dataLines = []
  for (const raw of String(eventText).split('\n')) {
    const line = raw.trimEnd()
    if (!line || line.startsWith(':')) continue
    if (!line.startsWith('data:')) continue
    let payload = line.slice(5)
    if (payload.startsWith(' ')) payload = payload.slice(1)
    dataLines.push(payload)
  }
  if (!dataLines.length) return
  const payload = dataLines.join('\n')
  if (payload && payload !== '[DONE]') onPayload(payload)
}
