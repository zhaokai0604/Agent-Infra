package com.award.log.config;

import jakarta.annotation.PostConstruct;
import lombok.Getter;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 运行期可热生效配置的内存态。
 */
@Getter
@Component
public class SystemConfigRuntimeState {

    public static final String DEFAULT_PING_TARGET = "8.8.8.8";

    private final Environment environment;

    private volatile List<String> patrolInspectRoots = List.of();
    private volatile List<Integer> healthCheckPorts = List.of();
    private volatile String pingTarget = DEFAULT_PING_TARGET;
    private volatile boolean autoRemediationEnabled;
    private volatile String autoRemediationMode = "HYBRID";
    private volatile boolean dryRunGlobal;
    private volatile double patrolDiskWarnPercent;
    private volatile double patrolCpuWarnPercent;
    private volatile double anomalySpikeFactor;
    private volatile int errorAlarmMin;
    private volatile double autoRiskPatrolAutoMax;
    private volatile double autoProposeTempCleanDiskMin;
    private volatile double autoProposeLogCleanDiskMin;

    public SystemConfigRuntimeState(Environment environment) {
        this.environment = environment;
    }

    @PostConstruct
    public void init() {
        patrolInspectRoots = SystemConfigFileSupport.stringList(
                environment.getProperty("ops.patrol.inspect-roots", "/var/log,/tmp"));
        healthCheckPorts = SystemConfigFileSupport.integerList(
                environment.getProperty("agent.autonomous.health-check-ports", "8080,80,443"));
        pingTarget = environment.getProperty("agent.autonomous.ping-target", DEFAULT_PING_TARGET);
        autoRemediationEnabled = Boolean.parseBoolean(environment.getProperty("ops.auto-remediation.enabled", "true"));
        autoRemediationMode = normalizeMode(environment.getProperty("ops.auto-remediation.run-mode", "HYBRID"));
        dryRunGlobal = Boolean.parseBoolean(environment.getProperty("ops.dry-run.global", "false"));
        patrolDiskWarnPercent = doubleProp("ops.patrol.disk-warn-percent", 80.0);
        patrolCpuWarnPercent = doubleProp("ops.patrol.cpu-warn-percent", 85.0);
        anomalySpikeFactor = doubleProp("ops.patrol.anomaly-spike-factor", 2.0);
        errorAlarmMin = intProp("ops.patrol.error-alarm-min", 3);
        autoRiskPatrolAutoMax = doubleProp("ops.auto-remediation.risk-patrol-auto-max", 6.0);
        autoProposeTempCleanDiskMin = doubleProp("ops.auto-remediation.propose-temp-clean-disk-min", 80.0);
        autoProposeLogCleanDiskMin = doubleProp("ops.auto-remediation.propose-log-clean-disk-min", 85.0);
    }

    public synchronized void setPatrolInspectRoots(List<String> patrolInspectRoots) {
        this.patrolInspectRoots = List.copyOf(patrolInspectRoots);
    }

    public synchronized void setHealthCheckPorts(List<Integer> healthCheckPorts) {
        this.healthCheckPorts = List.copyOf(healthCheckPorts);
    }

    public synchronized void setPingTarget(String pingTarget) {
        this.pingTarget = pingTarget;
    }

    public synchronized void setAutoRemediationEnabled(boolean autoRemediationEnabled) {
        this.autoRemediationEnabled = autoRemediationEnabled;
    }

    public synchronized void setAutoRemediationMode(String autoRemediationMode) {
        this.autoRemediationMode = normalizeMode(autoRemediationMode);
    }

    public synchronized void setDryRunGlobal(boolean dryRunGlobal) {
        this.dryRunGlobal = dryRunGlobal;
    }

    public synchronized void setPatrolDiskWarnPercent(double patrolDiskWarnPercent) {
        this.patrolDiskWarnPercent = patrolDiskWarnPercent;
    }

    public synchronized void setPatrolCpuWarnPercent(double patrolCpuWarnPercent) {
        this.patrolCpuWarnPercent = patrolCpuWarnPercent;
    }

    public synchronized void setAnomalySpikeFactor(double anomalySpikeFactor) {
        this.anomalySpikeFactor = anomalySpikeFactor;
    }

    public synchronized void setErrorAlarmMin(int errorAlarmMin) {
        this.errorAlarmMin = errorAlarmMin;
    }

    public synchronized void setAutoRiskPatrolAutoMax(double autoRiskPatrolAutoMax) {
        this.autoRiskPatrolAutoMax = autoRiskPatrolAutoMax;
    }

    public synchronized void setAutoProposeTempCleanDiskMin(double autoProposeTempCleanDiskMin) {
        this.autoProposeTempCleanDiskMin = autoProposeTempCleanDiskMin;
    }

    public synchronized void setAutoProposeLogCleanDiskMin(double autoProposeLogCleanDiskMin) {
        this.autoProposeLogCleanDiskMin = autoProposeLogCleanDiskMin;
    }

    public String patrolInspectRootsCsv() {
        return String.join(",", patrolInspectRoots);
    }

    public String healthCheckPortsCsv() {
        return healthCheckPorts.stream().map(String::valueOf).reduce((a, b) -> a + "," + b).orElse("");
    }

    private double doubleProp(String key, double defaultValue) {
        try {
            return Double.parseDouble(environment.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private int intProp(String key, int defaultValue) {
        try {
            return Integer.parseInt(environment.getProperty(key, String.valueOf(defaultValue)));
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String normalizeMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return "HYBRID";
        }
        String value = raw.trim().toUpperCase(Locale.ROOT);
        return switch (value) {
            case "IMMEDIATE", "CONFIRM_FIRST", "HYBRID" -> value;
            default -> "HYBRID";
        };
    }
}
