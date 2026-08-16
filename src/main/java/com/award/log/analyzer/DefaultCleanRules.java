package com.award.log.analyzer;

import java.util.ArrayList;
import java.util.List;

/**
 * 默认日志清洗规则
 * 包含常见的日志清洗规则，用于在分析前对日志进行预处理
 */
public class DefaultCleanRules {

    /**
     * 获取默认的清洗规则列表
     * @return 清洗规则列表
     */
    public static List<LogCleaner.CleanRule> getDefaultRules() {
        List<LogCleaner.CleanRule> rules = new ArrayList<>();
        
        // 1. 空白字符清理规则
        LogCleaner.CleanRule whitespaceRule = new LogCleaner.CleanRule();
        whitespaceRule.setId("whitespace");
        whitespaceRule.setName("空白字符清理");
        whitespaceRule.setType("whitespace");
        whitespaceRule.setEnabled(true);
        rules.add(whitespaceRule);

        // 1b. Linux 终端 / docker logs 常见 ANSI 颜色序列（减少对 Drain 分词与关键词匹配的干扰）
        LogCleaner.CleanRule ansiRule = new LogCleaner.CleanRule();
        ansiRule.setId("strip_ansi");
        ansiRule.setName("移除ANSI颜色序列");
        ansiRule.setType("regex");
        ansiRule.setPattern("\\x1b\\[[0-9;]*m");
        ansiRule.setReplacement("");
        ansiRule.setEnabled(true);
        rules.add(ansiRule);
        
        // 2. 时间戳标准化规则
        LogCleaner.CleanRule timeRule = new LogCleaner.CleanRule();
        timeRule.setId("time");
        timeRule.setName("时间戳标准化");
        timeRule.setType("time");
        timeRule.setTimeFormat("yyyy-MM-dd HH:mm:ss");
        timeRule.setEnabled(true);
        rules.add(timeRule);
        
        // 3. 敏感信息脱敏规则
        // 3.1 IP地址脱敏
        LogCleaner.CleanRule ipRule = new LogCleaner.CleanRule();
        ipRule.setId("ip_mask");
        ipRule.setName("IP地址脱敏");
        ipRule.setType("regex");
        ipRule.setPattern("(\\d{1,3}\\.\\d{1,3})(\\.\\d{1,3}\\.\\d{1,3})");
        ipRule.setReplacement("$1.***.***");
        ipRule.setEnabled(true);
        rules.add(ipRule);
        
        // 3.2 手机号脱敏
        LogCleaner.CleanRule phoneRule = new LogCleaner.CleanRule();
        phoneRule.setId("phone_mask");
        phoneRule.setName("手机号脱敏");
        phoneRule.setType("regex");
        phoneRule.setPattern("(1[3-9]\\d)\\d{4}(\\d{4})");
        phoneRule.setReplacement("$1****$2");
        phoneRule.setEnabled(true);
        rules.add(phoneRule);
        
        // 3.3 邮箱脱敏
        LogCleaner.CleanRule emailRule = new LogCleaner.CleanRule();
        emailRule.setId("email_mask");
        emailRule.setName("邮箱脱敏");
        emailRule.setType("regex");
        emailRule.setPattern("(\\w)\\w{2,}(\\w)@(\\w+\\.\\w+)");
        emailRule.setReplacement("$1***$2@$3");
        emailRule.setEnabled(true);
        rules.add(emailRule);
        
        // 4. 常见日志格式清理规则
        // 4.1 移除多余的引号
        LogCleaner.CleanRule quoteRule = new LogCleaner.CleanRule();
        quoteRule.setId("remove_quotes");
        quoteRule.setName("移除多余引号");
        quoteRule.setType("regex");
        quoteRule.setPattern("\"([^\"]+)\"");
        quoteRule.setReplacement("$1");
        quoteRule.setEnabled(true);
        rules.add(quoteRule);
        
        // 4.2 移除括号内的无关信息（暂时禁用，避免影响关键词检测）
        LogCleaner.CleanRule bracketRule = new LogCleaner.CleanRule();
        bracketRule.setId("remove_brackets");
        bracketRule.setName("移除括号内无关信息");
        bracketRule.setType("regex");
        bracketRule.setPattern("\\[.*?\\]");
        bracketRule.setReplacement("");
        bracketRule.setEnabled(false);
        rules.add(bracketRule);
        
        // 5. 系统特定规则
        // 5.1 Windows事件日志清理（暂时禁用，避免影响关键词检测）
        LogCleaner.CleanRule windowsRule = new LogCleaner.CleanRule();
        windowsRule.setId("windows_log");
        windowsRule.setName("Windows事件日志清理");
        windowsRule.setType("regex");
        windowsRule.setPattern("EventID=\\d+ ");
        windowsRule.setReplacement("");
        windowsRule.setEnabled(false);
        rules.add(windowsRule);
        
        // 5.2 Linux系统日志清理（暂时禁用，避免影响关键词检测）
        LogCleaner.CleanRule linuxRule = new LogCleaner.CleanRule();
        linuxRule.setId("linux_log");
        linuxRule.setName("Linux系统日志清理");
        linuxRule.setType("regex");
        linuxRule.setPattern("\\w+\\[\\d+\\]: ");
        linuxRule.setReplacement("");
        linuxRule.setEnabled(false);
        rules.add(linuxRule);
        
        // 6. 应用日志清理规则
        // 6.1 移除线程信息
        LogCleaner.CleanRule threadRule = new LogCleaner.CleanRule();
        threadRule.setId("remove_thread");
        threadRule.setName("移除线程信息");
        threadRule.setType("regex");
        threadRule.setPattern("\\[Thread-\\d+\\] ");
        threadRule.setReplacement("");
        threadRule.setEnabled(true);
        rules.add(threadRule);
        
        // 6.2 移除类名信息（暂时禁用，避免影响关键词检测）
        LogCleaner.CleanRule classRule = new LogCleaner.CleanRule();
        classRule.setId("remove_class");
        classRule.setName("移除类名信息");
        classRule.setType("regex");
        classRule.setPattern("\\w+\\.\\w+\\.\\w+:");
        classRule.setReplacement("");
        classRule.setEnabled(false);
        rules.add(classRule);
        
        return rules;
    }
    
    /**
     * 获取增强的关键词列表
     * @return 关键词类别及其对应的关键词列表
     */
    public static java.util.Map<String, java.util.List<String>> getEnhancedKeywords() {
        java.util.Map<String, java.util.List<String>> keywordsMap = new java.util.HashMap<>();
        
        // 致命错误
        keywordsMap.put("致命错误", java.util.Arrays.asList(
            "致命", "崩溃", "fatal", "crash", "系统崩溃", "system crash", 
            "unexpected death", "核心转储", "core dump", "segmentation fault", 
            "段错误", "系统中止", "system abort", "致命异常", "fatal exception"
        ));
        
        // 内存异常
        keywordsMap.put("内存异常", java.util.Arrays.asList(
            "内存溢出", "OutOfMemoryError", "内存不足", "memory leak", "内存泄漏", 
            "heap space", "stack overflow", "内存分配失败", "memory allocation failed", 
            "内存耗尽", "out of memory", "内存碎片", "memory fragmentation"
        ));
        
        // 认证失败
        keywordsMap.put("认证失败", java.util.Arrays.asList(
            "权限拒绝", "认证失败", "Permission denied", "Authentication failure", 
            "Failed password", "登录失败", "login failed", "unauthorized", 
            "access denied", "权限不足", "insufficient permissions", "认证超时", 
            "authentication timeout", "无效凭证", "invalid credentials"
        ));
        
        // 网络异常
        keywordsMap.put("网络异常", java.util.Arrays.asList(
            "无法连接", "超时", "Connection refused", "timeout", "端口不可达", 
            "网络错误", "network error", "connection reset", "连接重置", 
            "网络中断", "network interruption", "DNS解析失败", "DNS resolution failed", 
            "网络延迟", "network latency", "丢包", "packet loss", "网络拥塞", 
            "network congestion"
        ));
        
        // 数据库异常
        keywordsMap.put("数据库异常", java.util.Arrays.asList(
            "数据库连接失败", "SQL错误", "MySQL down", "Oracle error", "主键冲突", 
            "死锁", "database error", "connection timeout", "数据库超时", 
            "事务失败", "transaction failed", "查询超时", "query timeout", 
            "数据库崩溃", "database crash", "表不存在", "table not found", 
            "索引失效", "index失效", "数据溢出", "data overflow"
        ));
        
        // 系统异常
        keywordsMap.put("系统异常", java.util.Arrays.asList(
            "空指针", "NullPointerException", "系统错误", "system error", "服务停止", 
            "service stopped", "系统重启", "system restart", "系统崩溃", 
            "system crash", "系统资源不足", "insufficient system resources", 
            "文件系统错误", "file system error", "磁盘空间不足", "disk space full", 
            "系统负载过高", "high system load"
        ));
        
        // 安全异常
        keywordsMap.put("安全异常", java.util.Arrays.asList(
            "未授权", "unauthorized", "安全漏洞", "security breach", "入侵", 
            "intrusion", "黑客攻击", "hacker attack", "病毒", "virus", 
            "恶意软件", "malware", "钓鱼", "phishing", "密码泄露", 
            "password leak", "数据泄露", "data breach", "安全扫描", 
            "security scan", "异常访问", "abnormal access"
        ));
        
        // 性能异常
        keywordsMap.put("性能异常", java.util.Arrays.asList(
            "响应缓慢", "slow response", "高负载", "high load", "性能下降", 
            "performance degradation", "CPU使用率高", "high CPU usage", 
            "内存使用率高", "high memory usage", "磁盘IO高", "high disk IO", 
            "网络带宽不足", "insufficient network bandwidth", "响应超时", 
            "response timeout", "处理延迟", "processing delay"
        ));
        
        // 应用异常
        keywordsMap.put("应用异常", java.util.Arrays.asList(
            "应用崩溃", "application crash", "服务不可用", "service unavailable", 
            "API错误", "API error", "接口超时", "interface timeout", "业务逻辑错误", 
            "business logic error", "数据验证失败", "data validation failed", 
            "配置错误", "configuration error", "依赖服务失败", "dependency failure", 
            "初始化失败", "initialization failed", "启动失败", "startup failed"
        ));

        // Linux 系统与内核（OOM、文件系统、systemd、审计等）
        keywordsMap.put("Linux系统与内核", java.util.Arrays.asList(
            "oom-killer", "out of memory", "killed process", "cannot allocate memory",
            "segfault", "segmentation fault", "sigsegv", "sigkill", "general protection fault",
            "kernel panic", "soft lockup", "hard lockup", "nmi watchdog", "call trace:",
            "i/o error", "input/output error", "buffer i/o error", "blk_update_request",
            "ext4-fs error", "xfs error", "xfs_iread", "read-only file system", "remount-ro",
            "systemd[", "failed to start", "start request repeated too quickly", "a start job is running",
            "apparmor=\"denied\"", "selinux: denied", "audit:", "dnf error", "apt-get: error",
            "nfs: server", "stale file handle", "connection reset by peer", "network unreachable"
        ));
        
        return keywordsMap;
    }
    
    /**
     * 获取增强的正则表达式模式
     * @return 正则表达式模式及其描述
     */
    public static java.util.Map<String, String> getEnhancedRegexPatterns() {
        java.util.Map<String, String> patterns = new java.util.HashMap<>();
        
        // IP地址模式
        patterns.put("IP地址", "\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}\\.\\d{1,3}");
        
        // 邮箱地址模式
        patterns.put("邮箱地址", "[a-zA-Z0-9._%+-]+@[a-zA-Z0-9.-]+\\.[a-zA-Z]{2,}");
        
        // 手机号模式
        patterns.put("手机号", "1[3-9]\\d{9}");
        
        // 时间戳模式（应用 ISO8601；Linux syslog 英文月）
        patterns.put("时间戳", "\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}:\\d{2}|(?:Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)\\s+\\d{1,2}\\s+\\d{2}:\\d{2}:\\d{2}");
        
        // UUID模式
        patterns.put("UUID", "[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}");
        
        // URL模式
        patterns.put("URL", "https?:\\/\\/[\\w\\-]+(\\.[\\w\\-]+)+([\\w\\-.,@?^=%&:/~+#]*[\\w\\-@?^=%&/~+#])?");
        
        // 错误码模式
        patterns.put("错误码", "ERROR\\d{4,6}");
        
        // 进程ID模式
        patterns.put("进程ID", "PID[:=]\\s*\\d+");
        
        // 线程ID模式
        patterns.put("线程ID", "Thread-\\d+");
        
        // 异常栈模式
        patterns.put("异常栈", "\\w+Exception:.*(\\n\\s+at.*)+");
        
        return patterns;
    }
}
