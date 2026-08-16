export const ASSISTANT_META_PREFIX = 'ASSISTANT_META:'
export const ASSISTANT_EVENT_PREFIX = 'ASSISTANT_EVENT:'

/** 解析助手 SSE：元数据与结构化事件不入正文 */
export function handleAssistantSsePayload (payload, { onMeta, onEvent, onContent }) {
  if (!payload) return
  if (payload.startsWith(ASSISTANT_META_PREFIX)) {
    try {
      const meta = JSON.parse(payload.slice(ASSISTANT_META_PREFIX.length))
      onMeta?.(meta)
    } catch {
      // ignore malformed meta
    }
    return
  }
  if (payload.startsWith(ASSISTANT_EVENT_PREFIX)) {
    try {
      const event = JSON.parse(payload.slice(ASSISTANT_EVENT_PREFIX.length))
      onEvent?.(event)
    } catch {
      // ignore malformed event
    }
    return
  }
  onContent?.(payload)
}

export const SECURITY_OUTCOME_LABELS = {
  EXECUTED: '已执行',
  DIAGNOSED: '只读诊断',
  PREVIEW: '预览待确认',
  PREVIEW_OR_WRITE_PENDING: '预览/待落地',
  READ_ONLY_SURFACE: '只读模式',
  NO_TOOL: '未调工具',
  NO_PENDING: '无待办',
  FAILED: '执行失败',
  ERROR: '执行失败'
}

export function securityOutcomeLabel (outcome) {
  if (!outcome) return ''
  return SECURITY_OUTCOME_LABELS[outcome] || outcome
}
