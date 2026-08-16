/**
 * 其它页面 → 工作台对话预填（CustomEvent，由 App.vue 监听）
 */

/** 已完成任务 summary.anomalyCount ≥ 此值时展示「对话深挖」入口 */
export const OPS_AGENT_ANOMALY_THRESHOLD = 15

export function dispatchOpsAgentPrefill(message) {
  const text = typeof message === 'string' ? message.trim() : ''
  if (!text) return
  window.dispatchEvent(new CustomEvent('ops-navigate-agent', { detail: { message: text } }))
}

export function shouldOfferAgentDeepDive(row) {
  if (!row || row.status !== 'COMPLETED' || !row.summary) return false
  const n = Number(row.summary.anomalyCount)
  return Number.isFinite(n) && n >= OPS_AGENT_ANOMALY_THRESHOLD
}

export function agentPrefillHighAnomaly(row) {
  const ac = row.summary?.anomalyCount ?? '?'
  const total = row.summary?.totalLogs ?? '?'
  const rate =
    row.summary?.anomalyRate != null
      ? `${(Number(row.summary.anomalyRate) * 100).toFixed(2)}%`
      : '?'
  return (
    `任务「${row.taskId}」已完成，但异常较多（异常 ${ac} / 总计 ${total}，约 ${rate}，文件「${row.fileName || '未知'}」）。` +
    `请协助归纳可能根因，并给出日志窗口、磁盘诊断与临时目录清理建议。`
  )
}

export function agentPrefillFailedTask(row) {
  return (
    `日志任务失败：「${row.taskId}」文件「${row.fileName || '未知'}」。` +
    `请协助排查：日志异常趋势、磁盘占用与临时目录清理建议。`
  )
}

export function agentPrefillEngineFailure(taskId, errorMsg) {
  const id = taskId || '未知'
  const err = errorMsg ? `报错：${errorMsg}。` : ''
  return (
    `日志分析失败（任务 ${id}）。${err}` +
    `请协助排查系统日志异常、磁盘空间与临时目录清理方案。`
  )
}
