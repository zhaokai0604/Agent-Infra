/** 溯源步骤 phase / title 中文映射 */

const PHASE_LABELS = {
  receive: '接收请求',
  perceive: '环境感知',
  reason: '推理决策',
  security: '安全校验',
  execute: '执行操作',
  verify: '结果验证',
  plan: '执行计划',
  confirm: '用户确认',
  cancel: '已取消',
  complete: '完成',
  error: '错误',
  intent: '意图识别',
  route: '路由选择',
  tool: '工具调用',
  result: '结果汇总',
  cot: '分析推理'
}

export function traceStepTitle (step) {
  if (!step || typeof step !== 'object') return '步骤'
  const phase = String(step.phase || step.title || '').trim()
  if (!phase) return step.step != null ? `步骤 ${step.step}` : '步骤'
  const key = phase.toLowerCase().replace(/[\s-]+/g, '')
  if (key === 'cot') {
    const detail = String(step.detail || step.message || step.summary || '')
    const matched = detail.match(/^\[Step\s*\d+\s*-\s*([^\]]+)\]/i)
    if (matched?.[1]) return step.step != null ? `[${step.step}] ${matched[1].trim()}` : matched[1].trim()
  }
  const localized = PHASE_LABELS[key] || PHASE_LABELS[phase.toLowerCase()]
  if (localized) {
    return step.step != null ? `[${step.step}] ${localized}` : localized
  }
  if (/^[\u4e00-\u9fff]/.test(phase)) return phase
  return phase.replace(/_/g, ' ')
}
