package com.award.log.platform;

import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * 麒麟 V11 生产环境：探测 MCP/巡检依赖的系统命令是否可用（只读 which -v）。
 */
@Slf4j
@Component
@Profile("kylin")
public class KylinCommandProbe implements ApplicationRunner {

    private static final String[] LINUX_COMMANDS = {
            "systemctl", "journalctl", "ss", "df", "du", "ps", "ping", "find", "crontab"
    };

    @Value("${platform.kylin.probe-commands:true}")
    private boolean probeEnabled;

    private volatile Map<String, Object> lastProbeResult = Map.of();

    @Override
    public void run(ApplicationArguments args) {
        if (!probeEnabled || OsRuntime.isWindows()) {
            return;
        }
        Map<String, Object> result = new LinkedHashMap<>();
        List<Map<String, String>> commands = new ArrayList<>();
        int missing = 0;
        for (String cmd : LINUX_COMMANDS) {
            boolean ok = commandExists(cmd);
            if (!ok) {
                missing++;
            }
            Map<String, String> row = new LinkedHashMap<>();
            row.put("command", cmd);
            row.put("available", String.valueOf(ok));
            commands.add(row);
        }
        result.put("commands", commands);
        result.put("missingCount", missing);
        result.put("readyForMcp", missing <= 2);
        lastProbeResult = Map.copyOf(result);

        if (missing > 0) {
            log.warn("[Kylin] 系统命令探测：{} 个缺失（MCP 部分能力将降级）", missing);
        } else {
            log.info("[Kylin] 系统命令探测：全部 {} 个命令可用", LINUX_COMMANDS.length);
        }
    }

    public Map<String, Object> getLastProbeResult() {
        return lastProbeResult;
    }

    private static boolean commandExists(String name) {
        try {
            ProcessBuilder pb = new ProcessBuilder("bash", "-c", "command -v " + name);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            boolean finished = p.waitFor(3, TimeUnit.SECONDS);
            if (!finished) {
                p.destroyForcibly();
                return false;
            }
            return p.exitValue() == 0;
        } catch (Exception e) {
            return false;
        }
    }
}
