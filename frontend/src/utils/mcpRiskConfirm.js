/**
 * MCP 安全门「需二次确认」时的可读文案（与后端 riskScore / riskDimensions 对齐）。
 */

import { formatRiskDimensions } from './structuredDataView'

export function buildMcpRiskConfirmMessage(result) {
  if (!result || typeof result !== 'object') {
    return '该操作需在前端二次确认后才会真正下发到本机工具（等同口令「确认执行」）。'
  }
  const parts = []
  if (result.message) parts.push(String(result.message))
  if (result.riskScore != null && Number.isFinite(Number(result.riskScore))) {
    parts.push(`风险评分：${result.riskScore} / 10`)
  }
  if (result.riskDimensions && typeof result.riskDimensions === 'object') {
    const dimText = formatRiskDimensions(result.riskDimensions)
    if (dimText) parts.push('评分明细：\n' + dimText)
  }
  if (result.riskExplanation) parts.push(String(result.riskExplanation))
  if (result.toolEffect && typeof result.toolEffect === 'object') {
    const te = result.toolEffect
    const action = te.action || '?'
    const target = [te.targetType, te.targetId].filter(Boolean).join(':')
    parts.push(`效果对象：${action}${target ? ' → ' + target : ''}（不可逆 ${te.irreversibility ?? '?'}）`)
  }
  if (parts.length === 0) {
    return '该操作存在风险，需在界面二次确认后执行（等同口令「确认执行」）。'
  }
  return parts.join('\n\n')
}
