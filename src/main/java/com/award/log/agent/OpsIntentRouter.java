package com.award.log.agent;

import com.award.log.service.impl.UnifiedAssistantService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 统一意图路由：决定走运维运行时编排还是 LLM 多步 Tool。
 * <p>
 * 优先级：规则命中 → 直接 Playbook；规则未命中 → 可选语义 LLM 兜底；
 * 仍不确定 → {@link Playbook#NONE}（交对话 LLM / 追问，不瞎编排）。
 * </p>
 */
@Component
public class OpsIntentRouter {

    private static final Pattern AUTONOMOUS_INTENT = Pattern.compile(
            "(自动运维|全自动|一键运维|一键巡检|自主运维|智能运维|自动察觉|自动发现|自动处理|自动修复|"
                    + "察觉.*问题|发现.*解决|detect\\s*and\\s*fix|autonomous\\s*ops|auto\\s*remediat)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 失败服务 / systemd → 全栈自主编排（含 service AWM） */
    private static final Pattern SERVICE_FAILURE_INTENT = Pattern.compile(
            "(服务|systemd|单元|unit).*(失败|挂了|异常|down|failed|未运行|not\\s*running)|"
                    + "(failed\\s*unit|systemctl\\s*--failed|服务挂了|服务不可用)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern PATROL_CONTINUE = Pattern.compile(
            "(继续处理|处理巡检|执行巡检|巡检待办|待确认方案|处理待办|继续处理巡检)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern CLEANUP_INTENT = Pattern.compile(
            "(清理|清除|删掉|删除|释放|修复).*(垃圾|空间|磁盘|日志|临时|tmp|temp|log)|"
                    + "(磁盘|空间|硬盘|根分区|c盘).*(满|不足|紧张|告警|满了)|"
                    + "(disk|space).*(full|low|cleanup)|"
                    + "cleanup.*(disk|log|temp)|"
                    + "(预览|查看|诊断).*(清理|清除).*(临时|tmp|temp|日志|磁盘)|"
                    + "磁盘满了|空间不够|清理临时|"
                    + "帮我清理|请清理一下|清理一下|清一下垃圾|清理临时文件|"
                    + "(扫描|扫).*(磁盘|c盘|空间|文件|目录|热点|盘)|"
                    + "(照片|图片|文件|相册).*(c盘|磁盘|空间|备份|整理)",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern CPU_INTENT = Pattern.compile(
            "(cpu|负载|卡顿|慢|性能|进程).*(高|满|异常|问题|过高|太多)|"
                    + "(高负载|cpu高|cpu满了|内存紧张|系统慢|负载过高|占用过高|CPU占用)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GENERAL_OPS_CHECK = Pattern.compile(
            "(检查|排查|诊断|体检|巡检|看看|分析).*(系统|服务器|主机|本机|机器|环境|状况)|"
                    + "(系统|服务器|主机|本机|电脑|环境).*(检查|排查|诊断|体检|健康|状态|怎么样|如何|正常)|"
                    + "健康检查|运维检查|全面检查|帮我运维|系统状态|检查本机|"
                    + "(帮我|请).*(检查|排查|诊断|体检|看看|管理|整理|运维).*(电脑|本机|系统)|"
                    + "(检查|排查|诊断|体检|看看).*(电脑|本机|系统)|"
                    + "运维管家|电脑管家|系统管家|帮我管理电脑|帮我检查电脑|帮我运维",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private static final Pattern EXECUTE_FOLLOWUP = Pattern.compile(
            "(执行清理|按预览执行|真实删除|确认执行|开始清理|执行删除|执行修复|立即清理|直接删除|立即删除|马上删除|"
                    + "删掉|删了|确认删除|真实重启|立即重启|执行处置|立即执行)",
            Pattern.CASE_INSENSITIVE);

    /** 用户明确只要预览、不要删除 */
    private static final Pattern PREVIEW_ONLY = Pattern.compile(
            "仅预览|只预览|不要删|不要删除|别删|先看看|预览一下|不要执行|dry-?run",
            Pattern.CASE_INSENSITIVE);

    /** 用户消息里带盘符绝对路径的删除意图，视为已确认写操作（仍过路径白名单）。 */
    private static final Pattern EXPLICIT_PATH_DELETE = Pattern.compile(
            "(删除|删掉|清除|移除)\\s*[「\"']?(/[\\w\\-.]+|[A-Za-z]:[/\\\\])",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /** 明确二次确认/强执行口令：才允许跳过「仅预览」默认。 */
    private static final Pattern DIRECT_CLEANUP_EXECUTE = Pattern.compile(
            "清理.*垃圾.*确认|确认.*清理.*垃圾|直接清理|立刻清理|马上清理|立即清理|真实清理|"
                    + "清垃圾并执行|删垃圾并执行|直接删|马上删",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private LlmPlaybookClassifier llmPlaybookClassifier;

    public enum Playbook {
        PATROL_AUTOMATION,
        DISK_CLEANUP,
        CPU_PRESSURE,
        PATROL_CONTINUATION,
        NONE
    }

    @Autowired(required = false)
    public void setLlmPlaybookClassifier(LlmPlaybookClassifier llmPlaybookClassifier) {
        this.llmPlaybookClassifier = llmPlaybookClassifier;
    }

    /**
     * 规则 + 可选语义兜底。对外主入口。
     */
    public Playbook resolve(String userMessage) {
        Playbook byRule = resolveByRules(userMessage);
        if (byRule != Playbook.NONE) {
            return byRule;
        }
        return resolveByLlm(userMessage, null);
    }

    /** 仅规则层（测例 / 对照语义兜底时使用）。 */
    public Playbook resolveByRules(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return Playbook.NONE;
        }
        if (PATROL_CONTINUE.matcher(userMessage).find()) {
            return Playbook.PATROL_CONTINUATION;
        }
        // 磁盘/CPU 等场景 Playbook 优先于宽泛的「自动运维」话术
        if (CLEANUP_INTENT.matcher(userMessage).find() || EXECUTE_FOLLOWUP.matcher(userMessage).find()) {
            return Playbook.DISK_CLEANUP;
        }
        if (CPU_INTENT.matcher(userMessage).find() && !CLEANUP_INTENT.matcher(userMessage).find()) {
            return Playbook.CPU_PRESSURE;
        }
        if (SERVICE_FAILURE_INTENT.matcher(userMessage).find()) {
            return Playbook.PATROL_AUTOMATION;
        }
        if (AUTONOMOUS_INTENT.matcher(userMessage).find()) {
            return Playbook.PATROL_AUTOMATION;
        }
        if (GENERAL_OPS_CHECK.matcher(userMessage).find()) {
            return Playbook.PATROL_AUTOMATION;
        }
        return Playbook.NONE;
    }

    public Playbook resolveFromContext(
            String userMessage,
            List<UnifiedAssistantService.ChatTurn> history) {
        Playbook direct = resolveByRules(userMessage);
        if (direct != Playbook.NONE) {
            return direct;
        }
        if (AssistantIntentSignals.isOpsProceed(userMessage, history)
                || AssistantIntentSignals.isComputerManagementIntent(userMessage, history)) {
            Playbook fromHistory = resolveByRules(
                    AssistantIntentSignals.recentConversationText(userMessage, history, 8));
            if (fromHistory != Playbook.NONE) {
                return fromHistory;
            }
        }
        String hint = history == null || history.isEmpty()
                ? null
                : AssistantIntentSignals.recentConversationText(userMessage, history, 6);
        return resolveByLlm(userMessage, hint);
    }

    private Playbook resolveByLlm(String userMessage, String contextHint) {
        if (llmPlaybookClassifier == null || !llmPlaybookClassifier.isAvailable()) {
            return Playbook.NONE;
        }
        return llmPlaybookClassifier.classify(userMessage, contextHint).orElse(Playbook.NONE);
    }

    public boolean shouldOrchestrate(String userMessage) {
        return resolve(userMessage) != Playbook.NONE;
    }

    public boolean shouldOrchestrateFromContext(
            String userMessage,
            List<UnifiedAssistantService.ChatTurn> history) {
        if (shouldOrchestrate(userMessage)) {
            return true;
        }
        return resolveFromContext(userMessage, history) != Playbook.NONE;
    }

    public boolean forceRemediate(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return false;
        }
        if (PREVIEW_ONLY.matcher(userMessage).find()) {
            return false;
        }
        if (EXECUTE_FOLLOWUP.matcher(userMessage).find()
                || EXPLICIT_PATH_DELETE.matcher(userMessage).find()
                || DIRECT_CLEANUP_EXECUTE.matcher(userMessage).find()) {
            return true;
        }
        // 「帮我清理」等仅表达意图：默认仍走预览，须「确认执行」或工具台二次确认后才真写
        return false;
    }
}
