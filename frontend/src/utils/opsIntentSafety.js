/**
 * 工作台本地意图安全：与后端 IntentRiskFilter / UnifiedAssistantService 口径对齐。
 * 防止「删除系统」等话术被 parseUserIntent 误路由到 SystemLoadTool 等只读工具。
 */

const HIGH_RISK_RE =
  /删库|drop\s+database|关闭防火墙|关防火墙|rm\s+-rf\s*\/?|格式化\s*磁盘|\bmkfs\b|\bfdisk\b|shutdown|poweroff|reboot|init\s+0|iptables\s+-[FX]|写入.*sudoers/i

const DESTRUCTIVE_CN_RE =
  /删除.{0,16}(整个|全部|所有|根|系统)|删.{0,4}(整个|全部|所有).{0,12}(系统|根|盘|文件)|清空.{0,8}(系统|根|盘)|破坏.{0,6}系统/i

const INJECTION_RE = /忽略.{0,8}(规则|安全|限制)|无视.{0,8}(安全|规则)|绕过.{0,8}(安全|审计)/i

/** 是否应在工作台拦截（不走本地 MCP 快捷路径） */
export function isWorkbenchHighRiskMessage(text) {
  const t = (text || '').trim()
  if (!t) return false
  if (HIGH_RISK_RE.test(t)) return true
  if (DESTRUCTIVE_CN_RE.test(t)) return true
  if (INJECTION_RE.test(t)) return true
  return false
}

/** 与后端 FailureInsight 安全码对齐，便于前端本地拦截后上报 */
export function classifyWorkbenchBlockCode(text) {
  const t = (text || '').trim()
  if (INJECTION_RE.test(t)) return 'INJECTION'
  if (HIGH_RISK_RE.test(t)) return 'HIGH_RISK_COMMAND'
  if (DESTRUCTIVE_CN_RE.test(t)) return 'HIGH_INTENT'
  return 'HIGH_INTENT'
}

export function workbenchHighRiskBlockMarkdown(text) {
  return (
    `### 操作已拒绝\n\n` +
    `检测到**高风险表述**，未执行任何系统操作。\n\n` +
    `- 您的输入：${(text || '').slice(0, 200)}\n` +
    `- 说明：请改用**查看、分析**类需求；如需执行修改操作，请通过「工具箱」并在确认后执行。\n` +
    `- 本拦截已尝试记入「执行链路 → 安全教训」。\n`
  )
}
