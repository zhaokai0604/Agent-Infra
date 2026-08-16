package com.award.log.mcp;

import com.award.log.config.OpsDryRunProperties;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;

@Slf4j
@Component
public class MinPrivilegeExecutor extends AbstractCommandExecutor {

    private static final String DEFAULT_RUN_AS_USER = "award-agent";

    private final String runAsUser;
    private final boolean minPrivilegeEnabled;
    private final OpsDryRunProperties opsDryRunProperties;

    public MinPrivilegeExecutor(
            @Value("${agent.run-as-user:" + DEFAULT_RUN_AS_USER + "}") String runAsUser,
            @Value("${agent.min-privilege.enabled:true}") boolean minPrivilegeEnabled,
            OpsDryRunProperties opsDryRunProperties,
            ExecutorService executorService
    ) {
        super(executorService);
        this.runAsUser = runAsUser != null && !runAsUser.isEmpty() ? runAsUser : DEFAULT_RUN_AS_USER;
        this.minPrivilegeEnabled = minPrivilegeEnabled;
        this.opsDryRunProperties = opsDryRunProperties;
        log.info("最小权限执行器初始化，运行用户: {}, minPrivilegeEnabled: {}, globalDryRun: {}",
                this.runAsUser, minPrivilegeEnabled, opsDryRunProperties.isGlobalDryRun());
    }

    public CommandResult executeSafely(List<String> command) {
        return executeSafely(command, commandTimeoutMillis());
    }

    /**
     * @param timeoutMillis 单轮进程等待超时（毫秒），例如 {@code du} 大目录扫描可适当增大。
     */
    public CommandResult executeSafely(List<String> command, long timeoutMillis) {
        CommandResult dryRun = tryGlobalDryRun(command);
        if (dryRun != null) {
            return dryRun;
        }
        if (!minPrivilegeEnabled) {
            return executeWithRetry(command, timeoutMillis);
        }
        if (requiresRootPrivilege(command)) {
            return executeWithPrivilegeCheck(command, timeoutMillis);
        }
        return executeAsUser(command, runAsUser, timeoutMillis);
    }

    public CommandResult executeWithRoot(List<String> command) {
        if (!requiresRootPrivilege(command)) {
            log.warn("命令不需要 root 权限，使用普通用户执行: {}", command);
            return executeAsUser(command, runAsUser);
        }
        return executeWithPrivilegeCheck(command, commandTimeoutMillis());
    }

    private CommandResult executeWithPrivilegeCheck(List<String> command, long timeoutMillis) {
        boolean hasPrivilege = checkSudoPrivilege(runAsUser, command);
        if (!hasPrivilege) {
            String errorMsg = String.format("用户 %s 没有执行命令的权限: %s", runAsUser, command);
            log.error(errorMsg);
            return new CommandResult(false, null, errorMsg, -1, false);
        }
        return executeAsUser(command, runAsUser, timeoutMillis);
    }

    public String getRunAsUser() {
        return runAsUser;
    }

    public boolean isMinPrivilegeEnabled() {
        return minPrivilegeEnabled;
    }

    private CommandResult tryGlobalDryRun(List<String> command) {
        if (opsDryRunProperties == null || !opsDryRunProperties.isGlobalDryRun()) {
            return null;
        }
        if (!opsDryRunProperties.looksLikeMutatingCommand(command)) {
            return null;
        }
        String simulated = opsDryRunProperties.simulateOutput(command);
        log.info("全局演练模式拦截写命令: {}", command);
        return new CommandResult(true, simulated, null, 0, false);
    }

    @Override
    protected boolean requiresRootPrivilege(List<String> command) {
        if (super.requiresRootPrivilege(command)) {
            return true;
        }
        if (!OsRuntime.isWindows()) {
            return false;
        }
        String joined = String.join(" ", command).toLowerCase(Locale.ROOT);
        String first = command.isEmpty() ? "" : command.get(0).toLowerCase(Locale.ROOT);
        return first.equals("net") && joined.contains(" stop ")
                || first.equals("sc") && (joined.contains(" stop ") || joined.contains(" start "))
                || joined.contains("restart-computer")
                || joined.contains("remove-item") && joined.contains("-recurse");
    }

    @Override
    protected CommandResult executeAsUser(List<String> command, String runAsUser, long timeoutMillis) {
        if (OsRuntime.isWindows() && runAsUser != null && !runAsUser.isBlank()) {
            return executeOnWindows(command, timeoutMillis);
        }
        return super.executeAsUser(command, runAsUser, timeoutMillis);
    }

    /**
     * Windows：无 sudo，可选 runas 降级为当前进程用户直接执行（与 min-privilege 关闭时一致）。
     */
    private CommandResult executeOnWindows(List<String> command, long timeoutMillis) {
        List<String> cmd = new ArrayList<>(command);
        if (!cmd.isEmpty() && "powershell".equalsIgnoreCase(cmd.get(0)) && cmd.size() > 1) {
            log.debug("Windows PowerShell 命令: {}", cmd);
        }
        return executeWithRetry(cmd, timeoutMillis);
    }
}
