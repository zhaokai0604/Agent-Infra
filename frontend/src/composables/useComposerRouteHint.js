import { computed, unref } from 'vue'
import {
  shouldUseNaturalLanguageAssistant,
  isOpsMissionIntent,
  isOpsProceedIntent,
  isComputerManagementIntent
} from '../utils/opsMissionIntent'
import { mcpToolDisplayName } from '../utils/mcpToolsMeta'

function guessTool(text) {
  const t = text.toLowerCase()
  if (/磁盘|空间|df|占满/.test(t)) return 'DiskTool'
  if (/热点|大文件|目录扫描/.test(t)) return 'DiskInsightTool'
  if (/负载|cpu|内存/.test(t)) return 'SystemLoadTool'
  if (/进程|pid/.test(t)) return 'ProcessTool'
  if (/日志|journal|syslog/.test(t)) return 'LogAnalysisTool'
  if (/清理|删除|临时/.test(t)) return 'CleanTempTool'
  if (/重启|nginx|服务/.test(t)) return 'ServiceRestartTool'
  if (/网络|ping|连通/.test(t)) return 'NetworkTool'
  return null
}

export function computeRouteHint(text) {
  const raw = (text || '').trim()
  if (!raw) {
    return { mode: 'idle', label: 'Enter 发送 · Shift+Enter 换行' }
  }
  if (isOpsMissionIntent(raw)) {
    return { mode: 'autonomous', label: '全面巡检 · 助手编排排查' }
  }
  if (isOpsProceedIntent(raw)) {
    return { mode: 'agent-auto', label: '确认执行 · 助手继续编排' }
  }
  if (isComputerManagementIntent(raw)) {
    return { mode: 'agent-auto', label: '运维管家 · 多步排查' }
  }
  const tool = guessTool(raw)
  if (tool && !shouldUseNaturalLanguageAssistant(raw)) {
    return {
      mode: 'agent-auto',
      label: `助手编排 · 优先使用 ${mcpToolDisplayName(tool) || '运维工具'}`
    }
  }
  return { mode: 'nl', label: '智能对话 · 流式回复' }
}

export function useComposerRouteHint(inputRef) {
  return computed(() => computeRouteHint(unref(inputRef)))
}
