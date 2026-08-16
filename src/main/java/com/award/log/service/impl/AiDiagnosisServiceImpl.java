package com.award.log.service.impl;

import com.award.log.agent.OpsReportFormat;
import com.award.log.analysis.AiDiagnosisContextBuilder;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.util.AiChatResponseSupport;
import com.award.log.service.AiDiagnosisService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.util.List;
import java.time.Duration;

import reactor.core.publisher.Flux;

@Slf4j
@Service
public class AiDiagnosisServiceImpl implements AiDiagnosisService {

    /**
     * 批量/全量诊断：证据约束（与 user 里「硬性事实」条数配合，抑制编造与套话）
     */
    private static final String BATCH_EVIDENCE_AND_ANTI_HALLUCINATION = """
            
            ## 证据与反幻觉（必须遵守，优先于套话与通用 SRE 模板）
            **【分析报告大忌】绝对禁止编造日志中未出现的时间点、时间段、错误条数、占比、频率汇总。** 凡涉及时间与数量，**只能**引用输入里每条日志印出的「时间」字段与「内容」原文中的时间戳/计数表述；需要概括时写成「依据上文共 N 条焦点日志（见【硬性事实】）」，不得自拟「今天上午」「一度激增」等无日志支撑的叙述。
            **时间与异常表述**：讨论某一现象时，**直接摘录或逐字引用**该行日志的时间字段及异常信息（异常类、错误码、关键短语）；禁止改写时间格式后冒充「原始观测」。
            **按时间区分问题**：若各行「时间」或内容显示为**不同时刻**的多类异常，必须**分小节**分别描述（可按时间顺序编号），**禁止**混写成同一事故、同一根因；跨行归纳须标明覆盖了哪些行#或哪些时间戳。
            **根因不确定时**：使用 **「可能原因」** 并标注置信度（低/中）；**禁止虚构「集中爆发」「短时激增」「大面积故障」** 等措辞，除非日志正文或「疑似原因」中明确包含频次/密度证据且你能引用原文。
            1. **禁止编造**：不得虚构 IP、版本、TraceId、事件 ID 等与上文矛盾的信息；统计数字仅来自「【硬性事实】」及各行字段。
            2. **命名来源**：组件/类/服务/线程/库/表名等，**须为日志「内容」字段中实际出现的子串**（可短摘引，勿改写拼写）；若片段中未出现，写「当前片段未出现明确 xxx」并列出需补充字段，**禁止自创组件名**。
            3. **结论必带证据**：在 **问题描述**、**根因分析**、**问题修复** 中，**每一条独立结论或假设**后必须紧跟一行 **「— 证据：**（行# 或 时间）+ 引用 ≤160 字原文 **」**；无证据则降级为「推测」并明示缺失信息。
            4. **修复须对准本日志**：Mitigation / Corrective 必须与上文日志中的**具体异常形态**挂钩（例如日志里的异常类名、errno、SQLState、连接关键字）；禁止仅输出「检查网络/重启服务/查看配置」等可脱离本文成立的空话；信息不足时写 **「需补充：」** 并列出缺口。
            5. **预防措施**：仅当能从本片段与证据合理推导时撰写；否则一句话说明「需基线/变更窗口/指标」即可，**勿堆砌与本片段无关的模板**。
            6. **回答范围（Grounding）**：全文**只能**讨论本消息中出现的「时间」「等级」「内容」「疑似原因」及【硬性事实】中的条数；**禁止**引入、暗示或当作已证实事实来叙述与本上下文**无关**的系统、事件、版本、主机或调用链；禁止把通用运维百科伪装成「针对本批日志的结论」。**禁止捏造**上文未出现的任何陈述。
            7. **真实时间**：各行「时间」为引擎从日志行解析所得；若显示 **「（未解析到标准时间戳…」** 占位符，**禁止编造**具体时刻，论述时间时只能引用该行「内容」内可见的时间戳或明确写「时间字段未解析」。
            """;

    private static final String SINGLE_LOG_EVIDENCE_RULES = """
            
            ## 证据与反幻觉（单行也必须遵守）
            - **时间与数量**：须与原消息中的「时间（引用字段）」及正文一致；禁止编造时刻或次数。
            - **回答范围**：三行答复只能解释本条日志正文；禁止引入与本条无关的系统、事故或背景当作事实。
            - 组件/类名须来自日志正文中的实际子串；禁止捏造系统或框架名称。
            - 三条输出中每一句须能指向日志中的具体词/异常类型；若只能推测，句首要写「疑似」并说明缺何字段。
            - **专家建议**须贴合本条日志的错误形态（错误码/异常名/关键词）；禁止与本条无关的通用运维段落。
            """;

    @Autowired
    private ChatModel chatModel;

    private static final String EMPTY_AI_REPLY =
            "AI 未返回有效内容，请稍后重试或检查模型配置。";

    private static String ensureDiagnosisText(String text, String fallback) {
        if (text == null || text.isBlank()) {
            return fallback;
        }
        return text;
    }

    private Prompt createPrompt(List<EnhancedLogParseResultEntity> focusLines,
                                List<EnhancedLogParseResultEntity> baselineLines) {
        StringBuilder logContent = new StringBuilder();
        logContent.append("【上下文边界】下列「时间」列为引擎从日志行解析的真实时间戳；若为「（未解析到标准时间戳…」请以该行「内容」内时间为准。"
                + " AI 须仅依据下文作答，**禁止捏造**，**禁止引用或展开与下文日志无关的事件或实体**。\n\n");
        logContent.append("【焦点异常日志】（按文件内时间顺序排列；内容已截断防止超长）\n");
        for (EnhancedLogParseResultEntity logEntry : focusLines) {
            String snippet = AiDiagnosisContextBuilder.truncateForPrompt(
                    logEntry.getDesensitizedLog(), AiDiagnosisContextBuilder.MAX_CHARS_PER_FOCUS_LINE);
            String ord = logEntry.getSourceLineIndex() >= 0
                    ? ("行#" + logEntry.getSourceLineIndex())
                    : "行序未知";
            logContent.append(String.format("- %s | 时间: %s | 等级: %s | 模板: %s | 内容: %s | 疑似原因: %s\n",
                    ord,
                    logEntry.getLogTime(),
                    logEntry.getSeverity(),
                    logEntry.getTemplateId(),
                    snippet,
                    String.join(" ", logEntry.getAnomalyReasons())));
        }

        if (baselineLines != null && !baselineLines.isEmpty()) {
            logContent.append("\n【对照：抽样正常/非致命日志】（用于区分业务噪声与需处置故障；请勿将对照行当作故障证据）\n");
            for (EnhancedLogParseResultEntity logEntry : baselineLines) {
                String snippet = AiDiagnosisContextBuilder.truncateForPrompt(
                        logEntry.getDesensitizedLog(), AiDiagnosisContextBuilder.MAX_CHARS_PER_BASELINE_LINE);
                String ord = logEntry.getSourceLineIndex() >= 0
                        ? ("行#" + logEntry.getSourceLineIndex())
                        : "行序未知";
                logContent.append(String.format("- %s | 时间: %s | 等级: %s | 内容: %s\n",
                        ord,
                        logEntry.getLogTime(),
                        logEntry.getSeverity(),
                        snippet));
            }
        }

        String systemPrompt = "你担任 **Incident Response Lead（事件响应负责人）** 兼 **Staff SRE**：输入为经脱敏的异常日志切片，须在 **证据边界内** 撰写技术备忘录；**答复范围仅限于用户消息中的日志字段与正文**，不得引入无关事实；**宁可承认信息不足，也不得编造或套用无关模板**。\n\n"
                + OpsReportFormat.markdownOutputSpecForPrompt() + "\n"
                + "输出必须严格遵循下列栏目 (使用 `###` 子章节标题):\n\n"
                + "### 问题等级\n"
                + "依据 **严重度（Severity）× 紧急度（Urgency）** 给出 Critical / High / Medium / Low；标注判定依据（仅使用输入中已有的时间与等级字段）。\n\n"
                + "### 问题描述\n"
                + "用 **现象 — 受影响实体 — 影响面** 撰写；「受影响实体」**必须为日志原文中的名称子串**。若日志对应**多个不同时间戳**的问题，**按时间分小节**叙述，**不得**合并为单一事件叙事。\n\n"
                + "### 根因分析\n"
                + "列出 **2–3 条 RCA 假设**（可对应不同时间点的问题），逐条 **置信度（高/中/低）**、**反证所需观测**；不确定时标题用 **「可能原因」**，每条 hypothesis 后必须有 **— 证据：** 引用（见下文总则）。**禁止**无证据使用「集中爆发」等表述。\n\n"
                + "### 问题修复\n"
                + "针对每条高置信度假设给出 **Mitigation** 与 **Corrective Fix**，须点名日志中出现的异常类型/关键字；验证步骤尽量可复制；禁止与本片段无关的宽泛建议。\n\n"
                + "### 预防措施\n"
                + "仅写与本片段证据相关的可度量项；不可套用与本日志无关的泛泛架构清单。\n"
                + BATCH_EVIDENCE_AND_ANTI_HALLUCINATION;

        int baselineCount = baselineLines == null ? 0 : baselineLines.size();
        String userPrompt = "【硬性事实】焦点异常日志条数 = " + focusLines.size()
                + "；对照样本条数 = " + baselineCount
                + "。**禁止编造**与此不符的错误总数、时段、占比或与各行「时间/内容」矛盾的陈述。"
                + " 报告中凡提及时刻须**直接引用**各行「时间」字段或「内容」内原文时间戳；**禁止自拟**时段叙事。\n\n"
                + "以下是系统检测到的异常日志片段（含可选正常对照），请执行专业级根因分析：\n"
                + logContent;
        
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        return new Prompt(List.of(systemMessage, userMessage));
    }

    @Override
    public String generateDiagnosis(List<EnhancedLogParseResultEntity> anomalies) {
        log.info("生成 AI 诊断报告，异常条目数: [{}]", anomalies == null ? 0 : anomalies.size());
        if (anomalies == null || anomalies.isEmpty()) {
            return "经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。";
        }
        try {
            String result = ensureDiagnosisText(
                    AiChatResponseSupport.textFrom(chatModel.call(createPrompt(anomalies, List.of()))),
                    EMPTY_AI_REPLY);
            log.info("AI 诊断报告生成成功");
            return result;
        } catch (Exception e) {
            log.error("AI Service 批量调用失败", e);
            return "诊断服务暂时不可用，请稍后再试。（错误信息：" + e.getMessage() + "）";
        }
    }

    @Override
    public String generateDiagnosisFromFullResult(List<EnhancedLogParseResultEntity> fullResult) {
        log.info("生成 AI 诊断报告（完整上下文），总行数: [{}]", fullResult == null ? 0 : fullResult.size());
        if (fullResult == null || fullResult.isEmpty()) {
            return "经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。";
        }
        List<EnhancedLogParseResultEntity> focus = AiDiagnosisContextBuilder.selectFocusLines(fullResult);
        if (focus.isEmpty()) {
            return "经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。";
        }
        List<EnhancedLogParseResultEntity> baseline = AiDiagnosisContextBuilder.selectBaselineLines(fullResult);
        try {
            String result = ensureDiagnosisText(
                    AiChatResponseSupport.textFrom(chatModel.call(createPrompt(focus, baseline))),
                    EMPTY_AI_REPLY);
            log.info("AI 诊断报告生成成功（含对照样本 {} 条）", baseline.size());
            return result;
        } catch (Exception e) {
            log.error("AI Service 批量调用失败", e);
            return "诊断服务暂时不可用，请稍后再试。（错误信息：" + e.getMessage() + "）";
        }
    }

    @Override
    public String diagnoseSingleLog(EnhancedLogParseResultEntity logEntry) {
        if (logEntry == null) {
            return "无效的日志数据。";
        }
        log.info("单条日志快速诊断，日志等级: [{}]", logEntry.getSeverity());
        
        String systemPrompt = "你是 **On-call Principal Engineer**。对单条异常日志执行 **极致压缩（Ultra-compressed）RCA**，面向已在 bridges / Slack 上的值班工程师。\n" +
                "**答复仅能依据下方「时间」「日志内容」等用户字段**，禁止捏造或引入无关事件。\n" +
                "输出必须严格为三行以内（不使用 Markdown 标题）：\n" +
                "1. **核心问题**：Symptom → **名称须摘自本条日志正文**（类/logger/主机名等）；勿自创组件名。无则写「片段未含组件名」。\n" +
                "2. **专家建议**：针对日志中出现的**具体**异常类型/错误码/关键字给出动作；禁止与本条无关的通用命令堆砌。\n" +
                "3. **风险预防**：一句可观测补强；若无法从本条推断则写「需补充字段：…」。\n" +
                "全文 ≤ 220 汉字当量；禁止空话套话与免责声明堆砌。\n" +
                SINGLE_LOG_EVIDENCE_RULES;
        String safeBody = AiDiagnosisContextBuilder.truncateForPrompt(logEntry.getDesensitizedLog(), 8000);
        String timeLine = logEntry.getLogTime() != null && !logEntry.getLogTime().isBlank()
                ? logEntry.getLogTime()
                : "（未提取到独立时间字段，勿编造时刻；仅从内容推断时需标注「疑似」）";
        String userPrompt = String.format("时间（引用字段，勿改写编造）: %s\n日志内容: %s\n等级: %s\n来源: %s",
                timeLine, safeBody, logEntry.getSeverity(), logEntry.getProtocol());

        try {
            Message systemMessage = new SystemMessage(systemPrompt);
            Message userMessage = new UserMessage(userPrompt);
            String result = ensureDiagnosisText(
                    AiChatResponseSupport.textFrom(chatModel.call(new Prompt(List.of(systemMessage, userMessage)))),
                    EMPTY_AI_REPLY);
            log.info("单条日志诊断成功");
            return result;
        } catch (Exception e) {
            log.error("单条日志诊断失败", e);
            return "诊断失败: " + e.getMessage();
        }
    }

    @Override
    public Flux<String> generateDiagnosisStream(List<EnhancedLogParseResultEntity> anomalies) {
        log.info("启动 AI 流式诊断报告生成...");
        if (anomalies == null || anomalies.isEmpty()) {
            return Flux.just("经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。");
        }
        try {
            return chatModel.stream(createPrompt(anomalies, List.of()))
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> {
                        log.error("AI Stream 调用发生异常", e);
                        return Flux.just("诊断服务中断: " + e.getMessage());
                    });
        } catch (Exception e) {
            log.error("AI Stream 启动失败", e);
            return Flux.just("流式诊断启动失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> generateDiagnosisStreamFromFullResult(List<EnhancedLogParseResultEntity> fullResult) {
        log.info("启动 AI 流式诊断报告生成（完整上下文）...");
        if (fullResult == null || fullResult.isEmpty()) {
            return Flux.just("经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。");
        }
        List<EnhancedLogParseResultEntity> focus = AiDiagnosisContextBuilder.selectFocusLines(fullResult);
        if (focus.isEmpty()) {
            return Flux.just("经过智能分析，未在日志中发现明显异常，系统运行看起来很健康。");
        }
        List<EnhancedLogParseResultEntity> baseline = AiDiagnosisContextBuilder.selectBaselineLines(fullResult);
        try {
            return chatModel.stream(createPrompt(focus, baseline))
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> {
                        log.error("AI Stream 调用发生异常", e);
                        return Flux.just("诊断服务中断: " + e.getMessage());
                    });
        } catch (Exception e) {
            log.error("AI Stream 启动失败", e);
            return Flux.just("流式诊断启动失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> generateSingleLogDiagnosisStream(EnhancedLogParseResultEntity logEntry) {
        if (logEntry == null) {
            return Flux.just("无效的日志数据。");
        }
        
        String systemPrompt = "角色：**Tier-2 Operations Analyst**。对单条日志做 **流式友好的三段式处置摘要**，供控制台侧边栏实时展示。\n" +
                "**仅依据用户提供的本条时间与正文**，禁止无关叙述与捏造。\n" +
                "硬性约束：恰好 **3 句完整陈述**，不用 Markdown 标题。\n" +
                "句 1 — **Hypothesis**：名称与机制须来自日志正文词汇；禁止虚构类名/服务名。\n" +
                "句 2 — **Remediation**：针对本条出现的异常/错误码；勿给脱离本文的通用排查清单。\n" +
                "句 3 — **Impact**：若无法从本条判断影响，明确写「无法从单条判断」。\n" +
                "禁止含糊形容词而不附着日志中的实体。\n" +
                SINGLE_LOG_EVIDENCE_RULES;

        String safeBody = AiDiagnosisContextBuilder.truncateForPrompt(logEntry.getDesensitizedLog(), 8000);
        String timeLine = logEntry.getLogTime() != null && !logEntry.getLogTime().isBlank()
                ? logEntry.getLogTime()
                : "（未提取到独立时间字段）";
        String userPrompt = String.format("时间（引用字段）: %s\n日志内容: %s\n日志等级: %s\n来源: %s",
                timeLine, safeBody, logEntry.getSeverity(), logEntry.getProtocol());

        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userPrompt);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));

        try {
            return chatModel.stream(prompt)
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("AI 诊断暂时不可用: " + e.getMessage()));
        } catch (Exception e) {
            return Flux.just("请求失败: " + e.getMessage());
        }
    }

    @Override
    public Flux<String> chatStream(String userMessageText) {
        if (userMessageText == null || userMessageText.isBlank()) {
            return Flux.just("请输入有效的运维问题描述。");
        }
        String systemPrompt = "## Persona\n" +
                "**Distinguished Cloud & Platform Engineer**：贯通 Linux（含企业发行版与麒麟系）、Windows Server、Kubernetes、容器运行时与托管中间件；熟悉 **Well-Architected** 可靠性支柱与 **CRE（Customer Reliability Engineering）** 沟通范式。\n\n" +
                "## Operating Principles\n" +
                "1. **Precision over verbosity**：术语对齐 CNCF / POSIX / ITIL 常用语义场；拒绝口语化堆砌。\n" +
                "2. **Executable artifacts**：默认给出 **可复制** 的 CLI / Yaml / RegEx / PromQL 级示例，并注释参数风险。\n" +
                "3. **Systems thinking**：从 **数据面 / 控制面 / 依赖拓扑** 三联视角拆解问题；指出单点故障与序列化瓶颈。\n" +
                "4. **Structured response**：Markdown；复杂议题采用 **现状评估 → 风险矩阵 → 分阶段路线图**。\n" +
                "5. **Risk disclosure**：涉及数据迁移、证书轮转、网络 ACL 变更时，显式列出 **Rollback & Verification**。\n" +
                "6. **Evidence when logs present**：若用户粘贴了日志/监控片段，**结论与组件名须引用原文**；**禁止编造**片段中未出现的时刻、条数、时段占比或「集中爆发」叙事；时间与数量只能来自日志原文或用户明确给出的统计。\n" +
                "7. **Temporal separation**：若日志含多个时间戳，按时间区分不同问题，勿混为一谈；不确定根因时写 **可能原因**，勿虚构爆发或激增。\n" +
                "8. **Grounding**：回复只能依据用户粘贴的日志/监控片段；**禁止**叙述片段中不存在的事件、时间、数量或组件；**禁止**无关扩展。";
        Message systemMessage = new SystemMessage(systemPrompt);
        Message userMessage = new UserMessage(userMessageText);
        Prompt prompt = new Prompt(List.of(systemMessage, userMessage));
        
        try {
             return chatModel.stream(prompt)
                    .map(AiChatResponseSupport::textFrom)
                    .timeout(Duration.ofSeconds(90))
                    .retry(1)
                    .onErrorResume(e -> Flux.just("服务异常: " + e.getMessage()));
        } catch (Exception e) {
             return Flux.just("请求失败: " + e.getMessage());
        }
    }
}
