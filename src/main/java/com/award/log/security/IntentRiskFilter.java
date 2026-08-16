package com.award.log.security;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.decision.CollaborativeDecisionService;
import com.award.log.decision.DecisionInput;
import com.award.log.decision.DecisionResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Set;
import java.util.regex.Pattern;

@Slf4j
@Component
public class IntentRiskFilter {

    private static final Pattern HIGH_RISK_PATTERN = Pattern.compile(
            "(删库|drop\\s+database|关闭防火墙|关防火墙|rm\\s+-rf\\s*/?|格式化磁盘|格式化.{0,12}(磁盘|硬盘|新盘|分区|整盘)|\\bmkfs\\b|\\bfdisk\\b|shutdown|poweroff|reboot|init\\s+0|"
                    + ":mkfs|一键下载.*执行|下载.*\\|\\s*bash|curl[^\\n]{0,400}\\|\\s*sh|wget[^\\n]{0,400}\\|\\s*sh|"
                    + "iptables\\s+-F|iptables\\s+-X|echo\\s+.*>\\s+/etc/sudoers|写入.*sudoers|"
                    + "删除.{0,16}(整个|全部|所有|根|系统)|删.{0,4}(整个|全部|所有).{0,12}(系统|根|盘|文件)|清空.{0,8}(系统|根|盘)|"
                    + "清空整机|统统清掉|全部抹掉|抹掉.{0,8}(系统|根|盘)|铲掉.{0,8}(目录|文件|用户)|"
                    + "数据库.{0,12}(干掉|删掉|清掉|抹掉|重来)|干掉.{0,8}(数据库|库)|把库.{0,6}(删|清|干)|"
                    + "根目录.{0,16}(清理|清空|删除)|清理.{0,12}根目录|全面.{0,8}根|全盘.{0,12}清理|清理.{0,12}全盘|"
                    + "清理.{0,24}(/var/lib/mysql|数据库目录|数据库|mysql)|/var/lib/mysql|mysql.{0,12}(清理|删除|备份)|"
                    + "忽略.{0,16}(路径|安全|限制|规则|检查)|绕过.{0,12}(路径|安全|策略|检查)|"
                    + "wipe\\s+the\\s+entire|format\\s+the\\s+disk|purge\\s+everything|"
                    + "删库跑路|断电式关机|防火墙规则清零|全部放行.*防火墙)",
            Pattern.CASE_INSENSITIVE
    );

    private static final Pattern MEDIUM_RISK_PATTERN = Pattern.compile(
            "(修改配置|重启服务|systemctl\\s+restart|chmod\\s+[0-7]{3,4}|chown\\s+-R|kill\\s+-9|userdel|groupdel|"
                    + "rm\\s+-f\\s+/|truncate\\s+-s\\s+0|"
                    // 普通「清理临时/腾磁盘」走产品确认与写门控，不在意图层抬 MEDIUM（否则易误拦对话）
                    + "直接删掉|踢一脚重启|临时目录.*删|真实删除|确认执行清理|确认删除)",
            Pattern.CASE_INSENSITIVE
    );

    /** 与高危意图语义对齐的短种子（Jaccard 词袋相似度命中即提升为 HIGH，缓解「换说法绕过」） */
    private static final String[] HIGH_RISK_SEED_PHRASES = {
            "删库", "删除根目录", "删除整个系统文件", "删除全部系统文件", "rm -rf /", "drop database",
            "关闭防火墙", "清空防火墙规则", "格式化磁盘", "一键下载执行", "curl bash", "wget sh", "shutdown now",
            "清空整机文件", "统统清掉", "全部抹掉", "抹掉系统盘", "根分区清空", "wipe entire root",
            "format the disk", "purge everything", "删库跑路", "铲掉用户目录", "防火墙清零",
            "格式化新磁盘", "根目录磁盘清理", "清理数据库目录", "忽略路径检查", "清理 /var/lib/mysql",
            "下载脚本并执行", "数据库整个干掉", "把数据库干掉重来"
    };

    private final CollaborativeDecisionService collaborativeDecisionService;
    private final PromptInjectionGuard promptInjectionGuard;
    private final IntentRiskMlClassifier trainedMl;
    private final boolean intentUseMl;
    private final boolean intentUseTrainedMl;
    private final double semanticJaccardMin;

    /** 实验/单测便捷构造（不加载训练模型；非 Spring 主注入入口）。 */
    public IntentRiskFilter(
            CollaborativeDecisionService collaborativeDecisionService,
            PromptInjectionGuard promptInjectionGuard,
            boolean intentUseMl,
            double semanticJaccardMin
    ) {
        this(collaborativeDecisionService, promptInjectionGuard, null, intentUseMl, false, semanticJaccardMin);
    }

    @Autowired
    public IntentRiskFilter(
            CollaborativeDecisionService collaborativeDecisionService,
            PromptInjectionGuard promptInjectionGuard,
            @Autowired(required = false) IntentRiskMlClassifier trainedMl,
            @org.springframework.beans.factory.annotation.Value("${agent.security.intent-use-ml:false}") boolean intentUseMl,
            @org.springframework.beans.factory.annotation.Value("${agent.security.intent-use-trained-ml:true}") boolean intentUseTrainedMl,
            @org.springframework.beans.factory.annotation.Value("${agent.security.intent-semantic-jaccard-min:0.55}") double semanticJaccardMin
    ) {
        this.collaborativeDecisionService = collaborativeDecisionService;
        this.promptInjectionGuard = promptInjectionGuard;
        this.trainedMl = trainedMl;
        this.intentUseMl = intentUseMl;
        this.intentUseTrainedMl = intentUseTrainedMl;
        this.semanticJaccardMin = Math.max(0.2, Math.min(0.9, semanticJaccardMin));
    }

    public RiskLevel evaluate(String userInstruction) {
        userInstruction = AgenticRiskScoreEngine.normalizeUtterance(userInstruction);
        log.info("开始风险评估，用户指令: {}", userInstruction);

        // 第一步：检测提示注入攻击
        if (promptInjectionGuard != null && promptInjectionGuard.isInjection(userInstruction)) {
            log.warn("检测到提示注入攻击，直接提升为 HIGH 风险");
            return RiskLevel.HIGH;
        }

        // 产品黄金路径 / 确认执行 / 工具构造串：意图层直接 LOW（写操作仍由 MCP 门控与确认态约束）
        if ((isBenignOpsOrConfirmUtterance(userInstruction) || isToolInstructionShape(userInstruction))
                && !HIGH_RISK_PATTERN.matcher(userInstruction).find()) {
            log.info("命中正常运维/确认/工具指令形态，意图定为 LOW");
            return RiskLevel.LOW;
        }

        RiskLevel mlLevel = null;
        double mlConfidence = 0;
        if (intentUseTrainedMl && trainedMl != null && trainedMl.isReady()) {
            IntentRiskMlClassifier.Prediction pred = trainedMl.predict(userInstruction);
            if (pred != null && pred.confidence() >= trainedMl.getMinConfidence()) {
                mlLevel = pred.level();
                mlConfidence = pred.confidence();
                log.info("训练意图模型预测: {} conf={}", pred.label(), String.format(java.util.Locale.ROOT, "%.3f", pred.confidence()));
            }
        }

        if (looksSemanticallyHighRisk(userInstruction)) {
            log.warn("语义相似度命中高危种子短语，提升为 HIGH 风险");
            return RiskLevel.HIGH;
        }

        RiskLevel riskLevel;
        if (intentUseMl && collaborativeDecisionService != null) {
            riskLevel = evaluateWithEnsemble(userInstruction);
        } else {
            riskLevel = evaluateWithRules(userInstruction);
        }

        // 训练模型与规则融合：规则已 LOW 时 ML 不得抬升；ML 不得单独打出 HIGH
        riskLevel = fuseTrainedMl(riskLevel, mlLevel, mlConfidence, userInstruction);

        log.info("风险评估完成，指令: {}, 风险等级: {}", userInstruction, riskLevel);
        return riskLevel;
    }

    /**
     * ML 仅作旁证：不得把规则 LOW 抬升；不得在规则非 HIGH 时单独给出 HIGH（对话会整轮拦截）。
     */
    private RiskLevel fuseTrainedMl(RiskLevel rulesLevel, RiskLevel mlLevel, double mlConfidence, String text) {
        if (mlLevel == null) {
            return rulesLevel;
        }
        if (rulesLevel == RiskLevel.LOW) {
            if (mlLevel != RiskLevel.LOW) {
                log.info("训练模型判 {} 但规则为 LOW，保持 LOW（conf={}）",
                        mlLevel, String.format(java.util.Locale.ROOT, "%.3f", mlConfidence));
            }
            return RiskLevel.LOW;
        }
        if (mlLevel == RiskLevel.HIGH && rulesLevel != RiskLevel.HIGH) {
            log.info("训练模型判 HIGH 但规则为 {}，不抬升为 HIGH（conf={}）",
                    rulesLevel, String.format(java.util.Locale.ROOT, "%.3f", mlConfidence));
            return rulesLevel;
        }
        if (isBenignOpsOrConfirmUtterance(text) || isToolInstructionShape(text)) {
            return rulesLevel;
        }
        return maxRisk(mlLevel, rulesLevel);
    }

    /** 工具切面构造串：执行 XxxTool 参数: ... — 不宜用自然语言意图模型直接定生死 */
    private static boolean isToolInstructionShape(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        return t.startsWith("执行 ") && t.contains("Tool") && t.contains("参数");
    }

    /** 黄金路径 / 巡检续办 / 预览清理 / 日常清临时等明确低危运维口令 */
    private static boolean isBenignOpsOrConfirmUtterance(String text) {
        if (text == null || text.isBlank()) {
            return false;
        }
        String t = text.trim();
        if ("确认执行".equals(t) || (t.startsWith("确认执行") && t.length() <= 24)) {
            return true;
        }
        if (t.contains("确认执行") && (t.contains("巡检") || t.contains("待办") || t.contains("继续处理"))) {
            return true;
        }
        if (t.contains("预览") && (t.contains("清理") || t.contains("删除") || t.contains("临时"))) {
            return true;
        }
        // 日常清临时/腾磁盘：意图 LOW；真实删除仍走写工具确认与治理
        if ((t.contains("清理临时") || t.contains("临时文件") || t.contains("旧日志"))
                && (t.contains("腾出") || t.contains("释放") || t.contains("磁盘") || t.contains("空间")
                || t.contains("预览") || t.contains("清理"))) {
            return !HIGH_RISK_PATTERN.matcher(t).find();
        }
        return BENIGN_OPS_HINT.matcher(t).find();
    }

    private static final Pattern BENIGN_OPS_HINT = Pattern.compile(
            "系统负载|占用最高|磁盘使用|占用热点|一键巡检|全面检查|健康状态|查(看|询)?进程|"
                    + "扫描占用|临时文件|清理预览|本机健康|运维管家|电脑体检|系统状态|分析.*日志|"
                    + "磁盘热点|端口健康|巡检待办|处理待确认",
            Pattern.CASE_INSENSITIVE);

    private RiskLevel evaluateWithEnsemble(String userInstruction) {
        try {
            RawLogEvent event = new RawLogEvent();
            event.setContent(userInstruction);
            event.setLevel("INFO");

            DecisionInput input = DecisionInput.builder()
                    .event(event)
                    .template("user_input")
                    .build();

            DecisionResult result = collaborativeDecisionService.decide(input);
            double confidence = result.getConfidence();

            RiskLevel riskLevel;
            if (confidence >= 0.8) {
                riskLevel = RiskLevel.HIGH;
            } else if (confidence >= 0.5) {
                riskLevel = RiskLevel.MEDIUM;
            } else {
                riskLevel = RiskLevel.LOW;
            }

            log.info("融合引擎评估完成，confidence: {}, 风险等级: {}", confidence, riskLevel);
            return riskLevel;

        } catch (Exception e) {
            log.error("融合引擎评估异常，降级为规则评估: {}", e.getMessage());
            return evaluateWithRules(userInstruction);
        }
    }

    private RiskLevel evaluateWithRules(String userInstruction) {
        if (HIGH_RISK_PATTERN.matcher(userInstruction).find()) {
            return RiskLevel.HIGH;
        }
        if (MEDIUM_RISK_PATTERN.matcher(userInstruction).find()) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    public boolean isHighRisk(String userInstruction) {
        return evaluate(userInstruction) == RiskLevel.HIGH;
    }

    public boolean requiresConfirmation(String userInstruction) {
        RiskLevel level = evaluate(userInstruction);
        return level == RiskLevel.HIGH || level == RiskLevel.MEDIUM;
    }

    private boolean looksSemanticallyHighRisk(String text) {
        if (text == null || text.length() < 4) {
            return false;
        }
        Set<String> doc = wordTokens(text);
        if (doc.size() < 2) {
            return false;
        }
        for (String seed : HIGH_RISK_SEED_PHRASES) {
            double jac = jaccard(doc, wordTokens(seed));
            if (jac >= semanticJaccardMin) {
                return true;
            }
        }
        return false;
    }

    private static RiskLevel maxRisk(RiskLevel a, RiskLevel b) {
        if (a == null) {
            return b;
        }
        if (b == null) {
            return a;
        }
        if (a == RiskLevel.HIGH || b == RiskLevel.HIGH) {
            return RiskLevel.HIGH;
        }
        if (a == RiskLevel.MEDIUM || b == RiskLevel.MEDIUM) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static Set<String> wordTokens(String s) {
        Set<String> out = new HashSet<>();
        String lower = s.toLowerCase();
        for (String p : lower.split("[^\\p{L}\\p{N}]+")) {
            if (p.length() >= 2) {
                out.add(p);
            }
        }
        StringBuilder han = new StringBuilder();
        for (int i = 0; i < lower.length(); i++) {
            char c = lower.charAt(i);
            if (Character.UnicodeScript.of(c) == Character.UnicodeScript.HAN) {
                han.append(c);
            }
        }
        String h = han.toString();
        for (int i = 0; i + 1 < h.length(); i++) {
            out.add(h.substring(i, i + 2));
        }
        return out;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() || b.isEmpty()) {
            return 0.0;
        }
        int inter = 0;
        for (String x : a) {
            if (b.contains(x)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union == 0 ? 0.0 : (double) inter / union;
    }
}
