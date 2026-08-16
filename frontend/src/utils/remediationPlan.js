/**
 * 从助手 Markdown 回复中解析「处置计划」小节，供产品化卡片展示。
 */
export function parseRemediationPlan (markdown) {
  if (!markdown || typeof markdown !== 'string') return null
  const match = markdown.match(/##\s*处置计划\s*\n([\s\S]*?)(?=\n##\s+|\n>\s*\*\*数据依据|$)/i)
  if (!match) return null
  const body = match[1].trim()
  if (!body) return null
  const lines = body.split('\n').map((l) => l.trim()).filter(Boolean)
  const items = lines
    .filter((l) => /^[-*•]\s+/.test(l) || /^\d+[.)]\s+/.test(l))
    .map((l) => l.replace(/^[-*•]\s+/, '').replace(/^\d+[.)]\s+/, '').trim())
  const hasPreview = /预览|dry[- ]?run|待确认|不会真实|仅预览/i.test(body)
  return {
    title: '处置计划',
    body,
    items: items.length ? items : [body.slice(0, 280)],
    previewOnly: hasPreview
  }
}

/** A diagnostic result with no findings must never expose a pending write plan. */
export function hasNoActionableFinding (markdown) {
  const text = typeof markdown === 'string' ? markdown : ''
  return /发现项\s*[：:]\s*[`*：:]?\s*0\b/i.test(text)
    || /本轮.{0,24}(?:没有|未|无).{0,24}(?:可编排|可执行|自动修复|修复步骤)/i.test(text)
    || /(?:未发现|没有发现|无).{0,24}(?:可执行修复|需要自动处置|修复步骤)/i.test(text)
}

/** 判断助手回复是否包含可确认的处置计划 */
export function hasActionableRemediationPlan (markdown) {
  // 巡检明确没有发现或没有可执行修复时，不能把模型/工具的泛化建议显示成待执行方案。
  if (hasNoActionableFinding(markdown)) {
    return false
  }
  const plan = parseRemediationPlan(markdown)
  if (!plan) return false
  return plan.previewOnly || /清理|删除|重启|修复|执行|remediat/i.test(plan.body)
}

/** 只有存在待确认写工具时，工具计划才是处置方案；其余都是只读诊断计划。 */
export function isRemediationToolPlan (event) {
  if (!event || typeof event !== 'object') return false
  if (event.planKind === 'DIAGNOSIS') return false
  const pending = Array.isArray(event.pendingWriteTools)
    ? event.pendingWriteTools.filter(Boolean)
    : []
  return pending.length > 0
}
