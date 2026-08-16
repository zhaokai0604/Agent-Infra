package com.award.log.governance;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 生产治理：资产分级 + 动作准入矩阵（麒麟 prod-kylin 默认可用）。
 */
@Data
@ConfigurationProperties(prefix = "ops.governance")
public class OpsGovernanceProperties {

    private boolean enabled = true;

    /** 交付 profile 标识，便于验收与 README 对齐 */
    private String deliveryProfile = "prod-kylin";

    private AssetTier defaultPathTier = AssetTier.NON_CORE;

    private AssetTier defaultServiceTier = AssetTier.NON_CORE;

    /** 服务名 → 资产分级（不含 .service 后缀） */
    private Map<String, AssetTier> serviceTiers = defaultServiceTiers();

    /** 路径前缀 → 资产分级（最长前缀匹配） */
    private Map<String, AssetTier> pathTiers = defaultPathTiers();

    private Map<String, ActionAdmissionRule> actions = defaultActions();

    @Data
    public static class ActionAdmissionRule {
        private List<AssetTier> allowedTiers = new ArrayList<>();
        private boolean autoAllowed = false;
        private long cooldownMs = 3_600_000L;
        private String verifyHint = "";
        private String rollbackHint = "";
    }

    private static Map<String, AssetTier> defaultServiceTiers() {
        Map<String, AssetTier> m = new LinkedHashMap<>();
        m.put("nginx", AssetTier.NON_CORE);
        m.put("httpd", AssetTier.NON_CORE);
        m.put("rsyslog", AssetTier.NON_CORE);
        m.put("syslog-ng", AssetTier.NON_CORE);
        m.put("php-fpm", AssetTier.NON_CORE);
        m.put("grafana-server", AssetTier.NON_CORE);
        m.put("prometheus", AssetTier.NON_CORE);
        m.put("node_exporter", AssetTier.NON_CORE);
        m.put("award-log", AssetTier.NON_CORE);
        m.put("redis", AssetTier.CORE_STATELESS);
        m.put("tomcat", AssetTier.CORE_STATELESS);
        m.put("mysqld", AssetTier.CORE_STATEFUL);
        m.put("mysql", AssetTier.CORE_STATEFUL);
        m.put("mariadb", AssetTier.CORE_STATEFUL);
        m.put("docker", AssetTier.CORE_STATEFUL);
        m.put("kubelet", AssetTier.CORE_STATEFUL);
        m.put("sshd", AssetTier.FORBIDDEN_AUTO);
        m.put("ssh", AssetTier.FORBIDDEN_AUTO);
        m.put("etcd", AssetTier.FORBIDDEN_AUTO);
        m.put("iptables", AssetTier.FORBIDDEN_AUTO);
        m.put("firewalld", AssetTier.FORBIDDEN_AUTO);
        return m;
    }

    private static Map<String, AssetTier> defaultPathTiers() {
        Map<String, AssetTier> m = new LinkedHashMap<>();
        m.put("/tmp", AssetTier.NON_CORE);
        m.put("/var/tmp", AssetTier.NON_CORE);
        m.put("/var/cache", AssetTier.NON_CORE);
        m.put("/var/log", AssetTier.NON_CORE);
        m.put("/var/log/journal", AssetTier.NON_CORE);
        m.put("/etc", AssetTier.FORBIDDEN_AUTO);
        m.put("/boot", AssetTier.FORBIDDEN_AUTO);
        m.put("/dev", AssetTier.FORBIDDEN_AUTO);
        m.put("/var/lib/mysql", AssetTier.FORBIDDEN_AUTO);
        m.put("/var/lib/pgsql", AssetTier.FORBIDDEN_AUTO);
        m.put("/var/lib/docker", AssetTier.FORBIDDEN_AUTO);
        m.put("/var/lib/kubelet", AssetTier.FORBIDDEN_AUTO);
        return m;
    }

    private static Map<String, ActionAdmissionRule> defaultActions() {
        Map<String, ActionAdmissionRule> m = new LinkedHashMap<>();

        // 临时/日志清理：白名单路径允许，但禁止 ALLOW_AUTO（真实写一律确认，堵住社会工程与窄自动旁路）
        ActionAdmissionRule temp = new ActionAdmissionRule();
        temp.setAllowedTiers(List.of(AssetTier.NON_CORE));
        temp.setAutoAllowed(false);
        temp.setCooldownMs(3_600_000L);
        temp.setVerifyHint("磁盘使用率回落或 du 热点缩小");
        temp.setRollbackHint("删除不可回滚；依赖审计与备份策略");
        m.put("temp-cleanup", temp);

        ActionAdmissionRule log = new ActionAdmissionRule();
        log.setAllowedTiers(List.of(AssetTier.NON_CORE));
        log.setAutoAllowed(false);
        log.setCooldownMs(3_600_000L);
        log.setVerifyHint("journal 无新增 FATAL；错误率下降");
        log.setRollbackHint("日志裁剪不可回滚");
        m.put("log-cleanup", log);

        ActionAdmissionRule restart = new ActionAdmissionRule();
        restart.setAllowedTiers(List.of(AssetTier.NON_CORE, AssetTier.CORE_STATELESS));
        restart.setAutoAllowed(false);
        restart.setCooldownMs(3_600_000L);
        restart.setVerifyHint("systemctl is-active 恢复；端口探测成功");
        restart.setRollbackHint("再次 restart 或回滚 unit 配置");
        m.put("service-restart", restart);

        return m;
    }
}
