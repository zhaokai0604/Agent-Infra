package com.award.log.config;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Locale;

/**
 * 全局演练模式：开启后所有疑似写操作命令仅返回模拟结果，不实际执行。
 */
@Getter
@Component
public class OpsDryRunProperties {

    @Value("${ops.dry-run.global:false}")
    private boolean global;

    public boolean isGlobalDryRun() {
        return global;
    }

    public void setGlobal(boolean global) {
        this.global = global;
    }

    /**
     * 判断命令是否可能产生写副作用（用于全局 dry-run 拦截）。
     */
    public boolean looksLikeMutatingCommand(List<String> command) {
        if (command == null || command.isEmpty()) {
            return false;
        }
        String joined = String.join(" ", command).toLowerCase(Locale.ROOT);
        String first = command.get(0).toLowerCase(Locale.ROOT);
        if (first.contains("clean") || first.contains("restart") || first.contains("kill")) {
            return true;
        }
        return joined.contains(" rm ")
                || joined.contains(" rm-")
                || joined.startsWith("rm ")
                || joined.contains("del ")
                || joined.contains("remove-item")
                || joined.contains("restart")
                || joined.contains("stop ")
                || joined.contains("kill ")
                || joined.contains("systemctl start")
                || joined.contains("systemctl stop")
                || joined.contains("systemctl restart")
                || joined.contains("docker stop")
                || joined.contains("docker rm")
                || joined.contains("truncate")
                || joined.contains("mv ")
                || joined.contains("chmod ")
                || joined.contains("chown ");
    }

    public String simulateOutput(List<String> command) {
        return "[DRY-RUN] 全局演练模式已启用，未实际执行: " + String.join(" ", command);
    }
}
