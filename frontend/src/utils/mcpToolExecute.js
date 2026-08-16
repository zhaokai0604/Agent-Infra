import { ElMessage, ElMessageBox } from 'element-plus'
import { executeMcpTool, confirmMcpExecute } from '../api'
import { applyMcpWriteConfirmParams } from './mcpWriteParams'
import { buildMcpRiskConfirmMessage } from './mcpRiskConfirm'
import { normalizeMcpToolResponse } from './mcpToolResult'

/**
 * 统一 MCP 工具执行：自动处理 needConfirm 二次确认与结果规范化。
 * @returns {Promise<object|null>} 规范化后的结果；用户取消时返回 null
 */
export async function runMcpToolExecute(toolName, parameters, options = {}) {
  const userMessage = options.userMessage || `工具控制台执行 ${toolName}`
  let result = await executeMcpTool(toolName, parameters, userMessage)

  if (result && result.needConfirm === true) {
    try {
      await ElMessageBox.confirm(buildMcpRiskConfirmMessage(result), '操作确认', {
        confirmButtonText: '确认执行',
        cancelButtonText: '取消',
        type: 'warning',
        closeOnClickModal: false,
        customClass: 'mcp-risk-confirm-box'
      })
    } catch {
      ElMessage.info('已取消执行')
      return null
    }
    result = await confirmMcpExecute(
      toolName,
      applyMcpWriteConfirmParams(toolName, parameters, '确认执行'),
      '确认执行',
      userMessage || '确认执行',
      result.confirmationId || result.traceId || '',
      result.capabilityToken || ''
    )
  }

  const normalized = normalizeMcpToolResponse(result)
  if (normalized.writeMismatch && normalized.writeMismatchMessage) {
    ElMessage.warning(normalized.writeMismatchMessage)
  }
  if (normalized.evidenceIncomplete && normalized.evidenceMessage) {
    ElMessage.warning(normalized.evidenceMessage)
  }
  return normalized
}
