package com.award.log.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 智能运维 Agent 集中配置：路径白名单、服务重启白名单、日志保护等。
 * <p>默认值面向 Linux / 麒麟 Kylin 常见部署；可通过 {@code application.yml} 或环境变量覆盖。</p>
 */
@Data
@ConfigurationProperties(prefix = "agent")
public class AgentOpsProperties {

    private Paths paths = Paths.linuxKylinDefaults();

    private ServiceRestart serviceRestart = ServiceRestart.linuxKylinDefaults();

    private LogSafety logSafety = LogSafety.defaults();

    @Data
    public static class Paths {
        private String policyVersion = "path-policy-v2-kylin";

        /** 日志读取、分析、tail、du 热点扫描 */
        private List<String> readPrefixes = list(
                "/var/log",
                "/var/log/journal",
                "/tmp",
                "/var/tmp",
                "/var/cache",
                "/opt",
                "/usr/local",
                "/run",
                "/srv",
                "/data"
        );

        /** CleanTempTool */
        private List<String> cleanRoots = list("/tmp", "/var/tmp", "/var/cache");

        /** LogCleanupTool */
        private List<String> logCleanupRoots = list("/var/log", "/var/log/journal");

        /** ConfigCheckTool（只读语法检查，不写配置） */
        private List<String> configPrefixes = list(
                "/etc/nginx/",
                "/etc/httpd/",
                "/etc/ssh/",
                "/etc/sysconfig/",
                "/etc/security/",
                "/etc/crontab",
                "/etc/cron.",
                "/etc/systemd/system/",
                "/etc/my.cnf",
                "/etc/redis/",
                "/etc/php-fpm.d/",
                "/etc/rsyslog.d/",
                "/etc/logrotate.d/"
        );

        private List<String> deniedSubstrings = list(
                "/etc/shadow",
                "/etc/gshadow",
                "/.ssh/",
                "/root/",
                "/boot/",
                "/etc/sudoers",
                "/etc/passwd"
        );

        private List<String> logProtectedSubstrings = list(
                "/var/lib/mysql",
                "/var/lib/mariadb",
                "/var/lib/pgsql",
                "/var/lib/postgresql",
                "ib_logfile",
                "ibdata",
                "binlog",
                "slow.log",
                "relay-log",
                "/var/lib/redis",
                "/var/lib/mongodb"
        );

        private List<String> privilegePrefixes = list(
                "/usr/bin",
                "/usr/sbin",
                "/bin",
                "/sbin",
                "/usr/local/bin",
                "/usr/lib",
                "/lib/systemd"
        );

        private List<String> windowsReadPrefixes = list(
                "C:/Windows/Logs",
                "C:/Windows/System32/winevt/Logs",
                "C:/inetpub/logs",
                "C:/ProgramData/logs",
                "C:/ProgramData/Microsoft/Windows/WER/ReportArchive",
                "C:/ProgramData/Microsoft/Windows/WER/ReportQueue",
                "C:/Users/Administrator/AppData/Local/Temp",
                "C:/Windows/Temp",
                "C:/Temp",
                "D:/Temp",
                "E:/Temp",
                "F:/Temp",
                "logs"
        );

        private List<String> windowsCleanRoots = list(
                "C:/Users/Administrator/AppData/Local/Temp",
                "C:/Windows/Temp",
                "C:/Temp",
                "D:/Temp",
                "E:/Temp",
                "F:/Temp"
        );

        private List<String> windowsLogCleanupRoots = list(
                "C:/Windows/Logs",
                "C:/Windows/System32/winevt/Logs",
                "C:/inetpub/logs",
                "C:/ProgramData/logs",
                "logs"
        );

        private List<String> windowsConfigPrefixes = list(
                "C:/Windows/System32/drivers/etc",
                "C:/ProgramData",
                "C:/Windows/System32/winevt/Logs"
        );

        private List<String> windowsPrivilegePrefixes = list(
                "C:/Windows/System32",
                "C:/Windows/SysWOW64",
                "C:/Program Files",
                "C:/Program Files (x86)"
        );

        public static Paths linuxKylinDefaults() {
            return new Paths();
        }
    }

    @Data
    public static class ServiceRestart {
        /**
         * 非关键、可重启的单元名（不含 .service）。
         * 刻意不含 sshd、mysqld、docker、kubelet 等关键组件。
         */
        private List<String> allowlist = list(
                "nginx",
                "httpd",
                "rsyslog",
                "syslog-ng",
                "cups",
                "php-fpm",
                "redis",
                "tomcat",
                "grafana-server",
                "prometheus",
                "node_exporter",
                "award-log"
        );

        public static ServiceRestart linuxKylinDefaults() {
            return new ServiceRestart();
        }
    }

    @Data
    public static class LogSafety {
        private boolean lsofCheckEnabled = true;

        public static LogSafety defaults() {
            return new LogSafety();
        }
    }

    private static List<String> list(String... items) {
        List<String> out = new ArrayList<>();
        for (String i : items) {
            if (i != null && !i.isBlank()) {
                out.add(i.trim());
            }
        }
        return out;
    }
}
