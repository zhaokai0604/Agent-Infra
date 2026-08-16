/**
 * 助手意图信号（与后端 AssistantIntentSignals 对齐）
 * 前端用于 MCP 快捷路径 vs SSE 流式对话的分流。
 */

/** 用户明确要求不走工具 / 仅文字 */
export function userDeclinesTools(text) {
  const t = (text || '').trim()
  if (!t) return false
  return /不调用工具|不要调用工具|不用工具|别用工具|禁止工具|不要工具|不要执行工具|仅文字|只要文字|纯对话|不要跑命令|不调工具|不用mcp/i.test(t)
}

const RE_GREETING = /^(你好|您好|嗨|哈喽|hello|hi|hey|在吗|在不在|早上好|下午好|晚上好|早安|晚安)[\s!！?？。,，~～…呀啊呐吗哇呢]*$/i
const RE_FAREWELL = /^(再见|拜拜|bye|goodbye|回见|先这样)[\s!！?？。,，~～…]*$/i
const RE_GRATITUDE = /^(谢谢|感谢|多谢|辛苦了|thanks|thank you)[\s!！?？。,，~～…]*$/i
const RE_ACK = /^(好的|好|收到|明白|了解|知道了|ok|okay|嗯嗯|行|可以|没问题|可以的|可以啊|行啊|开始吧|来吧)[\s!！?？。,，~～…]*$/i
const RE_OPS_PROCEED = /^(可以的|可以啊|行啊|没问题啊|好的呀|嗯好|开始吧|动手吧|来吧|继续|继续吧|执行吧|查吧|扫吧)[\s!！?？。,，~～…]*$|直接扫描|开始扫描|执行扫描|立即扫描|马上扫描|扫描一下|扫一下|扫描磁盘|扫描C盘|先扫描|先查一下/i
const RE_OPS_CONTEXT = /磁盘|空间|硬盘|C盘|c盘|临时|日志|照片|图片|文件|扫描|检查|排查|诊断|体检|清理|释放|热点|目录|占用|满了|备份|盘符|文件夹|微信|相册|电脑|本机|系统/i
const RE_CAPABILITY = /^(你是谁|你叫什么|你能做什么|你能干嘛|你会什么|有什么功能|介绍一下|介绍自己)[\s?？!！。,，~～…]*$/i
const RE_PUNCT_ONLY = /^[?？!！。…~～.]+$/
const RE_CANCEL = /^(取消|不要了|算了|停止|别做了|不用了|先不|暂停)[\s!！?？。,，~～…]*$|不要执行|别执行|停止生成/i
// 「帮我清理」仅表达意图，不算确认；须命中下列明确写意图词才为 CONFIRM_WRITE
const RE_CONFIRM_WRITE = /确认执行|执行修复|按预览执行|开始清理|执行删除|真实删除|立即执行|执行处置|删掉|删了|直接删除|立即删除|马上删除|确认删除|真实重启|立即重启|执行清理|立即清理|直接清理|马上删/i
const RE_PREVIEW_ONLY = /仅预览|只预览|不要删|不要删除|别删|先看看|预览一下|不要执行|dry-?run|演练|模拟/i
const RE_USAGE_HELP = /怎么用|如何使用|怎么开始|从哪里开始|使用说明|操作步骤|帮助文档|新手引导|入门/
const RE_FOLLOW_UP = /刚才|之前|上面|上一个|那条|这个|那个|接着|然后呢|还有呢|继续说说|再详细|展开说/
const RE_EXPLAIN = /[？?]|为什么|怎么回事|什么原因|怎么(会|回事)|如何理解|含义|说明一下|是什么意思|是否|能否|有没有/
const RE_SUMMARIZE = /总结|概括|归纳|梳理一下|简要|三句话|一句话/
const RE_COMPARE = /哪个更好|区别|对比|差异|优缺点|选哪个/
const RE_CORRECTION = /不对|错了|不是这个|理解错了|重新来|再来一次|答非所问/
const RE_METRICS_QUERY = /(查|看|查询|检查|看看|分析|诊断|排查|监控|统计).*(cpu|内存|磁盘|网络|负载|进程|状态)|(cpu|内存|磁盘|网络|负载|进程).*(多少|多高|使用率|占用|情况|怎么样|如何)/i
const RE_OPS_KEYWORDS = /磁盘|空间|cpu|内存|进程|负载|日志|网络|服务|巡检|清理|重启|docker|systemd|防火墙|端口|配置|告警|异常|故障|修复|执行|分析|检查|诊断|运维|主机|容器|性能|占用|满了|down|failed|一键|自动|挂了|不可用|扫描|照片|图片|文件|C盘|c盘|文件夹|热点|备份/i
const RE_PATROL = /自动运维|自主运维|一键运维|一键巡检|智能运维|全自动|健康检查|运维检查|全面检查|帮我运维|(检查|排查|诊断|体检|巡检|看看).*(系统|服务器|主机|本机|电脑|机器|环境)|(系统|服务器|主机|本机|电脑).*(检查|排查|诊断|体检|健康|状态|怎么样|如何|正常)|(帮我|请).*(检查|排查|诊断|体检|看看).*(电脑|本机|系统)|检查系统|排查问题/
const RE_PATROL_CONTINUE = /继续处理|处理巡检|执行巡检|巡检待办|待确认方案|处理待办|继续处理巡检/
const RE_COMPUTER_MANAGE = /(帮我|请|麻烦|能否|可以).*(管理|整理|扫描|清理|备份|释放|查找|搜索|删除|优化|检查|排查|诊断|体检).*(电脑|本机|系统|磁盘|文件|照片|图片|目录|C盘|c盘|文件夹|空间|运维)|(电脑|本机|系统|磁盘).*(管理|整理|扫描|清理|备份|优化|检查|排查|诊断|体检|帮忙|怎么办)|(整理|备份|扫描|清理|释放|查找).*(照片|图片|文件|磁盘|空间|目录|文件夹|相册)|帮我管理|运维管家|电脑管家|系统管家|自动整理|接手.*电脑|管理我的电脑|帮我运维/

/** 打招呼、告别、致谢、确认收到、能力询问 */
export function isGreetingOrChitchat(text) {
  const t = (text || '').trim()
  if (!t) return false
  return (
    RE_GREETING.test(t) ||
    RE_FAREWELL.test(t) ||
    RE_GRATITUDE.test(t) ||
    RE_ACK.test(t) ||
    RE_CAPABILITY.test(t) ||
    RE_PUNCT_ONLY.test(t)
  )
}

export function isCancelIntent(text) {
  return RE_CANCEL.test((text || '').trim())
}

export function isConfirmWriteIntent(text) {
  return RE_CONFIRM_WRITE.test((text || '').trim())
}

export function isPreviewOnlyIntent(text) {
  return RE_PREVIEW_ONLY.test((text || '').trim())
}

export function isMetricsQueryIntent(text) {
  const t = (text || '').trim()
  return RE_METRICS_QUERY.test(t) && !RE_CONFIRM_WRITE.test(t)
}

/** 运维管家 / 本机管理 — 与后端 isComputerManagementIntent 对齐 */
export function isComputerManagementIntent(text, priorMessages = []) {
  const t = (text || '').trim()
  if (!t || userDeclinesTools(t)) return false
  if (isGreetingOrChitchat(t)) return false
  if (RE_COMPUTER_MANAGE.test(t)) return true
  if (RE_OPS_KEYWORDS.test(t)) {
    if (isMetricsQueryIntent(t) && !/清理|删除|扫描|满了|空间不够|照片|图片|文件/.test(t)) return false
    return true
  }
  if (t.length > 80 && recentMessagesHaveOpsContext(priorMessages)) return true
  return false
}

/** 解释、追问、总结 — 与后端 CONVERSATION 对齐 */
export function isConversationIntent(text, hasHistory = false) {
  const t = (text || '').trim()
  if (!t) return false
  if (userDeclinesTools(t)) return true
  if (isComputerManagementIntent(t)) return false
  if (isGreetingOrChitchat(t)) return true
  if (isCancelIntent(t)) return true
  if (RE_CORRECTION.test(t)) return true
  if (RE_USAGE_HELP.test(t)) return true
  if (RE_FOLLOW_UP.test(t)) return true
  if (RE_EXPLAIN.test(t)) return true
  if (RE_SUMMARIZE.test(t)) return true
  if (RE_COMPARE.test(t)) return true
  if (RE_PUNCT_ONLY.test(t) && hasHistory) return true
  if (t.length > 140) return true
  return false
}

/** 一句话自主运维 / 全面检查 */
export function isOpsMissionIntent(text) {
  const t = (text || '').trim()
  if (!t || userDeclinesTools(t)) return false
  return RE_PATROL.test(t) || RE_PATROL_CONTINUE.test(t)
}

/** 续办 / 直接扫描 — 与后端 isOpsProceed 对齐 */
export function isOpsProceedIntent(text, priorMessages = []) {
  const t = (text || '').trim()
  if (!t || userDeclinesTools(t)) return false
  if (RE_OPS_PROCEED.test(t)) {
    if (t.length <= 24) {
      return RE_OPS_CONTEXT.test(t) || recentMessagesHaveOpsContext(priorMessages)
    }
    return true
  }
  return false
}

export function recentMessagesHaveOpsContext(messages = []) {
  const text = messages.slice(-6).map(m => m?.content || '').join(' ')
  return RE_OPS_CONTEXT.test(text)
}

/** 用户在看完计划后确认落地写操作 */
export function userConfirmedRemediation(text) {
  return isConfirmWriteIntent(text)
}

/**
 * 是否走助手 SSE 流式对话（而非前端 MCP 快捷路径）
 * 寒暄/追问/编排/确认均走 SSE；仅明确短指令可走 MCP 快捷路径
 */
export function shouldUseNaturalLanguageAssistant(text, hasHistory = false) {
  const t = (text || '').trim()
  if (!t) return true
  if (isComputerManagementIntent(t)) return true
  if (isOpsProceedIntent(t)) return true
  if (isConversationIntent(t, hasHistory)) return true
  if (isOpsMissionIntent(t)) return true
  if (userConfirmedRemediation(t)) return true
  if (isPreviewOnlyIntent(t)) return true
  if (isMetricsQueryIntent(t)) return true
  // 含运维关键词但较长 → SSE
  if (RE_OPS_KEYWORDS.test(t) && t.length > 20) return true
  return false
}

/** 供调试：返回前端推断的意图标签（与后端 intentCategory 近似） */
export function inferIntentCategory(text, hasHistory = false) {
  const t = (text || '').trim()
  if (!t) return 'EMPTY'
  if (userConfirmedRemediation(t)) return 'CONFIRM_WRITE'
  if (isCancelIntent(t)) return 'CANCEL'
  if (userDeclinesTools(t)) return 'DECLINE_TOOLS'
  if (RE_GREETING.test(t)) return 'GREETING'
  if (RE_FAREWELL.test(t)) return 'FAREWELL'
  if (RE_GRATITUDE.test(t)) return 'GRATITUDE'
  if (RE_ACK.test(t)) return 'ACKNOWLEDGMENT'
  if (RE_CAPABILITY.test(t)) return 'CAPABILITY_INQUIRY'
  if (RE_USAGE_HELP.test(t)) return 'USAGE_HELP'
  if (RE_PUNCT_ONLY.test(t)) return hasHistory ? 'CLARIFICATION' : 'CLARIFICATION'
  if (RE_CORRECTION.test(t)) return 'CORRECTION'
  if (RE_FOLLOW_UP.test(t)) return 'FOLLOW_UP'
  if (RE_EXPLAIN.test(t)) return 'EXPLANATION'
  if (RE_SUMMARIZE.test(t)) return 'SUMMARIZATION'
  if (RE_COMPARE.test(t)) return 'COMPARISON'
  if (isPreviewOnlyIntent(t)) return 'PREVIEW_ONLY'
  if (isMetricsQueryIntent(t)) return 'METRICS_QUERY'
  if (isOpsMissionIntent(t)) return 'PATROL_ORCHESTRATE'
  if (RE_OPS_KEYWORDS.test(t)) return 'OPS_DIAGNOSIS'
  if (isComputerManagementIntent(t)) return 'COMPUTER_MANAGE'
  return 'GENERAL'
}
