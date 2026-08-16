package com.award.log.agent;

import com.award.log.service.impl.UnifiedAssistantService;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 助手意图信号库：集中维护所有正则与判定方法，供 Planner / 前端对齐。
 * <p>判定顺序见 {@link AssistantReplyPlanner}。</p>
 */
public final class AssistantIntentSignals {

    private AssistantIntentSignals() {
    }

    // ── 寒暄 / 社交 ──
    public static final Pattern GREETING = Pattern.compile(
            "^(你好|您好|hello|hi|hey|嗨|哈喽|在吗|在不在|早上好|下午好|晚上好|早安|晚安)"
                    + "[\\s!！?？。,，~～…呀啊呐吗哇呢]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern FAREWELL = Pattern.compile(
            "^(再见|拜拜|bye|goodbye|回见|先这样|下了|撤了)[\\s!！?？。,，~～…]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern GRATITUDE = Pattern.compile(
            "^(谢谢|感谢|多谢|辛苦了|thanks|thank you|太棒了|不错)[\\s!！?？。,，~～…呀啊]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern ACKNOWLEDGMENT = Pattern.compile(
            "^(好的|好|收到|明白|了解|知道了|ok|okay|嗯嗯|行|可以|没问题)[\\s!！?？。,，~～…]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 用户确认继续 / 要求立即动手（少追问、先执行） */
    public static final Pattern OPS_PROCEED = Pattern.compile(
            "^(可以的|可以啊|行啊|没问题啊|好的呀|嗯好|开始吧|动手吧|来吧|继续|继续吧|执行吧|查吧|扫吧)[\\s!！?？。,，~～…]*$|"
                    + "直接扫描|开始扫描|执行扫描|立即扫描|马上扫描|扫描一下|扫一下|扫描磁盘|扫描C盘|扫描 c盘|"
                    + "开始查|马上查|执行排查|先扫描|先查一下|现在就扫|现在就查",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 对话上下文中出现运维/磁盘/文件相关词 */
    public static final Pattern OPS_CONTEXT = Pattern.compile(
            "(磁盘|空间|硬盘|C盘|c盘|临时|日志|照片|图片|文件|扫描|检查|排查|诊断|体检|清理|释放|热点|目录|占用|满了|备份|盘符|文件夹|微信|相册|电脑|本机|系统)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern CAPABILITY_INQUIRY = Pattern.compile(
            "^(你是谁|你叫什么|你能做什么|你能干嘛|你会什么|有什么功能|介绍一下|介绍自己|你能帮我什么)"
                    + "[\\s?？!！。,，~～…]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern USAGE_HELP = Pattern.compile(
            "(怎么用|如何使用|怎么开始|从哪里开始|使用说明|操作步骤|帮助文档|新手引导|入门)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── 对话 / 追问 ──
    public static final Pattern PUNCT_ONLY = Pattern.compile("^[?？!！。…~～\\.]+$");

    public static final Pattern CLARIFICATION = Pattern.compile(
            "^(什么意思|没听懂|没明白|看不懂|再说一遍|重复一下|解释一下)[\\s?？!！。]*$|^[?？]$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern FOLLOW_UP = Pattern.compile(
            "刚才|之前|上面|上一个|那条|这个|那个|接着|然后呢|还有呢|继续说说|再详细|展开说",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern EXPLANATION = Pattern.compile(
            "[？?]|为什么|怎么回事|什么原因|怎么(会|回事)|如何理解|含义|说明一下|是什么意思|是否|能否|有没有",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern SUMMARIZATION = Pattern.compile(
            "总结|概括|归纳|梳理一下|简要|三句话|一句话",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern COMPARISON = Pattern.compile(
            "哪个更好|区别|对比|差异|优缺点|选哪个",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern CORRECTION = Pattern.compile(
            "不对|错了|不是这个|理解错了|重新来|再来一次|答非所问",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern CANCEL = Pattern.compile(
            "^(取消|不要了|算了|停止|别做了|不用了|先不|暂停)[\\s!！?？。,，~～…]*$|不要执行|别执行|停止生成",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── 工具 / 执行偏好 ──
    public static final Pattern DECLINE_TOOLS = Pattern.compile(
            "不调用工具|不要调用工具|不用工具|别用工具|禁止工具|不要工具|不要执行工具|仅文字|只要文字|纯对话|不要跑命令|不调工具|不用mcp",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern CONFIRM_WRITE = Pattern.compile(
            "确认执行|执行修复|按预览执行|开始清理|执行删除|真实删除|立即执行|执行处置|删掉|删了|直接删除|立即删除|马上删除|确认删除|真实重启|立即重启",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern PREVIEW_ONLY = Pattern.compile(
            "仅预览|只预览|不要删|不要删除|别删|先看看|预览一下|不要执行|dry-?run|演练|模拟",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern READ_ONLY_VERB = Pattern.compile(
            "(查|看|查询|检查|看看|分析|诊断|排查|监控|统计).*(cpu|内存|磁盘|网络|负载|进程|状态)|"
                    + "(cpu|内存|磁盘|网络|负载|进程).*(多少|多高|使用率|占用|情况|怎么样|如何)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    // ── 运维 / 编排 ──
    public static final Pattern OPS_KEYWORDS = Pattern.compile(
            "(磁盘|空间|cpu|内存|进程|负载|日志|网络|服务|巡检|清理|重启|docker|systemd|防火墙|端口|配置|告警|异常|故障|修复|执行|分析|检查|诊断|运维|主机|容器|性能|占用|满了|down|failed|一键|自动|挂了|不可用|扫描|照片|图片|文件|C盘|c盘|文件夹|热点|备份)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern PATROL_ORCHESTRATE = Pattern.compile(
            "自动运维|自主运维|一键运维|一键巡检|智能运维|全自动|健康检查|运维检查|全面检查|帮我运维|"
                    + "(检查|排查|诊断|体检|巡检|看看).*(系统|服务器|主机|本机|电脑|机器|环境)|"
                    + "(系统|服务器|主机|本机|电脑).*(检查|排查|诊断|体检|健康|状态|怎么样|如何|正常)|"
                    + "(帮我|请).*(检查|排查|诊断|体检|看看).*(电脑|本机|系统)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern PATROL_CONTINUATION = Pattern.compile(
            "继续处理|处理巡检|执行巡检|巡检待办|待确认方案|处理待办|继续处理巡检",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static final Pattern STRONG_OPS_ACTION = Pattern.compile(
            "(磁盘|空间|硬盘).*(满|不足|紧张|告警)|"
                    + "(cpu|负载).*(高|满|异常|过高|占用)|"
                    + "(内存).*(高|不足|紧张|oom)|"
                    + "清理|删除|重启|修复|处置",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 运维管家 / 文件整理 / 本机管理 */
    public static final Pattern COMPUTER_MANAGE = Pattern.compile(
            "(帮我|请|麻烦|能否|可以).*(管理|整理|扫描|清理|备份|释放|查找|搜索|删除|优化|检查|排查|诊断|体检).*(电脑|本机|系统|磁盘|文件|照片|图片|目录|C盘|c盘|文件夹|空间|运维)|"
                    + "(电脑|本机|系统|磁盘).*(管理|整理|扫描|清理|备份|优化|检查|排查|诊断|体检|帮忙|怎么办)|"
                    + "(整理|备份|扫描|清理|释放|查找).*(照片|图片|文件|磁盘|空间|目录|文件夹|相册)|"
                    + "帮我管理|运维管家|电脑管家|系统管家|自动整理|接手.*电脑|管理我的电脑|帮我运维",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    public static boolean isBlank(String msg) {
        return msg == null || msg.isBlank();
    }

    public static boolean hasHistory(List<UnifiedAssistantService.ChatTurn> history) {
        return history != null && !history.isEmpty();
    }

    public static AssistantIntentCategory classifySocial(String msg) {
        if (GREETING.matcher(msg).matches()) return AssistantIntentCategory.GREETING;
        if (FAREWELL.matcher(msg).matches()) return AssistantIntentCategory.FAREWELL;
        if (GRATITUDE.matcher(msg).matches()) return AssistantIntentCategory.GRATITUDE;
        if (ACKNOWLEDGMENT.matcher(msg).matches()) return AssistantIntentCategory.ACKNOWLEDGMENT;
        if (CAPABILITY_INQUIRY.matcher(msg).matches()) return AssistantIntentCategory.CAPABILITY_INQUIRY;
        return null;
    }

    public static boolean isConversationalIntent(String msg, List<UnifiedAssistantService.ChatTurn> history) {
        if (DECLINE_TOOLS.matcher(msg).find()) return true;
        if (USAGE_HELP.matcher(msg).find()) return true;
        // 运维管家类任务不走纯对话，即使含「能否/为什么」或较长描述
        if (isComputerManagementIntent(msg, history)) return false;
        if (CLARIFICATION.matcher(msg).matches()) return true;
        if (PUNCT_ONLY.matcher(msg).matches() && hasHistory(history)) return true;
        if (FOLLOW_UP.matcher(msg).find()) return true;
        if (EXPLANATION.matcher(msg).find()) return true;
        if (SUMMARIZATION.matcher(msg).find()) return true;
        if (COMPARISON.matcher(msg).find()) return true;
        if (CORRECTION.matcher(msg).find()) return true;
        if (CANCEL.matcher(msg).find()) return true;
        if (msg.length() > 140) return true;
        return false;
    }

    /**
     * 用户希望 Agent 真正操作本机（扫描、整理、清理等），而非仅文字建议。
     */
    public static boolean isComputerManagementIntent(String msg, List<UnifiedAssistantService.ChatTurn> history) {
        if (isBlank(msg)) {
            return false;
        }
        String trimmed = msg.trim();
        if (classifySocial(trimmed) != null) {
            return false;
        }
        if (DECLINE_TOOLS.matcher(trimmed).find()) {
            return false;
        }
        if (COMPUTER_MANAGE.matcher(trimmed).find()) {
            return true;
        }
        if (OPS_KEYWORDS.matcher(trimmed).find()) {
            if (isMetricsQuery(trimmed) && !STRONG_OPS_ACTION.matcher(trimmed).find()) {
                return false;
            }
            return true;
        }
        if (trimmed.length() > 80 && hasOpsContext(trimmed, history)) {
            return true;
        }
        return false;
    }

    public static boolean isMetricsQuery(String msg) {
        return READ_ONLY_VERB.matcher(msg).find()
                && !STRONG_OPS_ACTION.matcher(msg).find()
                && !CONFIRM_WRITE.matcher(msg).find();
    }

    /** 多指标综合查询交给 Tool Agent，由模型根据首轮结果决定是否继续取数。 */
    public static boolean isBroadMetricsQuery(String msg) {
        if (!isMetricsQuery(msg)) {
            return false;
        }
        String value = msg == null ? "" : msg.toLowerCase();
        int domains = 0;
        for (String domain : List.of("cpu", "内存", "磁盘", "进程", "网络", "端口", "服务")) {
            if (value.contains(domain)) {
                domains++;
            }
        }
        return domains >= 3;
    }

    public static boolean isPreviewOnly(String msg) {
        return PREVIEW_ONLY.matcher(msg).find();
    }

    public static String recentConversationText(String current,
                                                List<UnifiedAssistantService.ChatTurn> history,
                                                int maxMessages) {
        StringBuilder sb = new StringBuilder(current == null ? "" : current.trim());
        if (history == null || history.isEmpty()) {
            return sb.toString();
        }
        int taken = 0;
        for (int i = history.size() - 1; i >= 0 && taken < maxMessages; i--) {
            UnifiedAssistantService.ChatTurn turn = history.get(i);
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            sb.append(' ').append(turn.content().trim());
            taken++;
        }
        return sb.toString();
    }

    public static boolean hasOpsContext(String msg, List<UnifiedAssistantService.ChatTurn> history) {
        if (msg != null && OPS_CONTEXT.matcher(msg).find()) {
            return true;
        }
        return OPS_CONTEXT.matcher(recentConversationText("", history, 6)).find();
    }

    /**
     * 用户确认继续或要求直接扫描 —— 应对「可以的」「直接扫描」等续办话术。
     */
    public static boolean isOpsProceed(String msg, List<UnifiedAssistantService.ChatTurn> history) {
        if (isBlank(msg)) {
            return false;
        }
        String trimmed = msg.trim();
        if (OPS_PROCEED.matcher(trimmed).find()) {
            if (trimmed.length() <= 24) {
                return hasOpsContext(trimmed, history);
            }
            return true;
        }
        if (OPS_KEYWORDS.matcher(trimmed).find()
                && Pattern.compile("(开始|直接|马上|立即|现在).*(扫描|查|执行|排查)",
                Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE).matcher(trimmed).find()) {
            return true;
        }
        return false;
    }
}
