package com.award.log.mcp;

import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.*;

@Slf4j
public abstract class AbstractCommandExecutor {

    private static final int ASYNC_COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_RETRIES = 2;
    private static final long RETRY_DELAY_MS = 500;
    /** 防止 df/journalctl 等洪泛输出撑爆堆内存；超出截断并标记 */
    private static final int MAX_STDOUT_CHARS = 512 * 1024;
    private static final int MAX_STDERR_CHARS = 256 * 1024;

    private static final ExecutorService STREAM_DRAIN_EXECUTOR = Executors.newCachedThreadPool(r -> {
        Thread t = new Thread(r, "mcp-stream-drain");
        t.setDaemon(true);
        return t;
    });

    protected final ExecutorService executor;

    protected AbstractCommandExecutor(ExecutorService executor) {
        this.executor = executor;
    }

    /** 同步执行单条命令时的默认超时（毫秒）；子类可覆盖，或通过 {@link #executeWithRetry(List, long)} 传入更大值。 */
    protected long commandTimeoutMillis() {
        return 10_000L;
    }

    /** @see McpDurationSupport#normalize(long) */
    protected long normalizeDurationMs(long durationOrStartTime) {
        return McpDurationSupport.normalize(durationOrStartTime);
    }

    protected CompletableFuture<CommandResult> executeCommandAsync(List<String> command) {
        return CompletableFuture
            .supplyAsync(() -> executeWithRetry(command, commandTimeoutMillis()), executor)
            .orTimeout(ASYNC_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex instanceof TimeoutException) {
                    log.warn("命令执行超时: {}", command);
                    return new CommandResult(false, null, "command timeout", -1, true);
                }
                log.error("命令执行异常: {}", ex.getMessage());
                return new CommandResult(false, null, ex.getMessage(), -1, false);
            });
    }

    protected CommandResult executeCommand(List<String> command) {
        return executeWithRetry(command, commandTimeoutMillis());
    }

    protected CompletableFuture<CommandResult> executeAsUserAsync(List<String> command, String runAsUser) {
        return CompletableFuture
            .supplyAsync(() -> executeAsUser(command, runAsUser, commandTimeoutMillis()), executor)
            .orTimeout(ASYNC_COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
            .exceptionally(ex -> {
                if (ex instanceof TimeoutException) {
                    log.warn("命令执行超时: {} (as user: {})", command, runAsUser);
                    return new CommandResult(false, null, "command timeout", -1, true);
                }
                log.error("命令执行异常: {}", ex.getMessage());
                return new CommandResult(false, null, ex.getMessage(), -1, false);
            });
    }

    protected CommandResult executeAsUser(List<String> command, String runAsUser) {
        return executeAsUser(command, runAsUser, commandTimeoutMillis());
    }

    protected CommandResult executeAsUser(List<String> command, String runAsUser, long timeoutMillis) {
        if (runAsUser == null || runAsUser.isEmpty()) {
            return executeWithRetry(command, timeoutMillis);
        }
        // Windows 无 sudo -u：直接以当前服务进程用户执行（与 application-dev 关闭 min-privilege 效果一致）
        if (OsRuntime.isWindows()) {
            log.debug("Windows 环境：跳过 sudo，直接执行 {}", command);
            return executeWithRetry(command, timeoutMillis);
        }

        List<String> sudoCommand = new java.util.ArrayList<>();
        sudoCommand.add("sudo");
        sudoCommand.add("-u");
        sudoCommand.add(runAsUser);
        sudoCommand.addAll(command);

        log.info("以用户 {} 执行命令: {}", runAsUser, command);
        return executeWithRetry(sudoCommand, timeoutMillis);
    }

    protected boolean requiresRootPrivilege(List<String> command) {
        String firstArg = command.get(0);
        String fullCommand = String.join(" ", command);

        // 检查需要root权限的命令
        return firstArg.equals("systemctl") ||
               firstArg.equals("docker") ||
               firstArg.equals("mount") ||
               firstArg.equals("umount") ||
               firstArg.equals("iptables") ||
               firstArg.equals("ifconfig") ||
               firstArg.equals("route") ||
               firstArg.equals("useradd") ||
               firstArg.equals("userdel") ||
               firstArg.equals("groupadd") ||
               firstArg.equals("groupdel") ||
               fullCommand.contains("sudo") ||
               fullCommand.contains("chmod 777") ||
               fullCommand.contains("rm -rf /");
    }

    protected boolean checkSudoPrivilege(String runAsUser, List<String> command) {
        if (OsRuntime.isWindows()) {
            return true;
        }
        try {
            List<String> checkCommand = List.of(
                "sudo", "-l", "-U", runAsUser
            );
            CommandResult result = executeWithRetry(checkCommand, 30_000L);
            if (!result.success()) {
                log.warn("检查 sudo 权限失败: {}", result.error());
                return false;
            }

            String sudoOutput = result.output();
            String commandStr = String.join(" ", command);
            return sudoOutput.contains(command.get(0));
        } catch (Exception e) {
            log.error("检查 sudo 权限异常: {}", e.getMessage());
            return false;
        }
    }

    /**
     * 供 {@link MinPrivilegeExecutor} 在关闭最小权限模式时直接执行原始命令。
     */
    protected CommandResult executeWithRetry(List<String> command) {
        return executeWithRetry(command, commandTimeoutMillis());
    }

    protected CommandResult executeWithRetry(List<String> command, long timeoutMillis) {
        long perAttemptTimeout = timeoutMillis > 0 ? timeoutMillis : commandTimeoutMillis();
        int attempts = 0;
        while (attempts <= MAX_RETRIES) {
            attempts++;
            try {
                CommandResult result = doExecute(command, perAttemptTimeout);
                if (result.success() || attempts > MAX_RETRIES) {
                    return result;
                }
                log.warn("命令执行失败，重试 {}/{}: {}", attempts, MAX_RETRIES, command);
                Thread.sleep(RETRY_DELAY_MS);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                return new CommandResult(false, null, "interrupted", -1, false);
            }
        }
        return new CommandResult(false, null, "max retries exceeded", -1, false);
    }

    private CommandResult doExecute(List<String> command, long timeoutMillis) {
        StringBuilder output = new StringBuilder();
        StringBuilder errorOutput = new StringBuilder();
        int exitCode = -1;

        try {
            ProcessBuilder pb = new ProcessBuilder(command);
            pb.redirectErrorStream(false);

            Process process = pb.start();

            CompletableFuture<Void> stdoutFuture = CompletableFuture.runAsync(
                    () -> drainStream(process.getInputStream(), output, MAX_STDOUT_CHARS, "stdout"),
                    STREAM_DRAIN_EXECUTOR);
            CompletableFuture<Void> stderrFuture = CompletableFuture.runAsync(
                    () -> drainStream(process.getErrorStream(), errorOutput, MAX_STDERR_CHARS, "stderr"),
                    STREAM_DRAIN_EXECUTOR);

            if (timeoutMillis <= 0) {
                CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
                exitCode = process.waitFor();
            } else {
                boolean finished = process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS);
                if (!finished) {
                    process.destroyForcibly();
                    stdoutFuture.cancel(true);
                    stderrFuture.cancel(true);
                    log.warn("命令执行超时 ({} ms): {}", timeoutMillis, command);
                    return new CommandResult(false, output.toString(), "command timeout", -1, true);
                }
                CompletableFuture.allOf(stdoutFuture, stderrFuture).join();
                exitCode = process.exitValue();
            }

            boolean success = exitCode == 0;
            String errorMsg = errorOutput.length() > 0 ? errorOutput.toString() : null;

            if (!success && errorMsg == null) {
                errorMsg = "exit code: " + exitCode;
            }

            return new CommandResult(success, output.toString(), errorMsg, exitCode, false);

        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            return new CommandResult(false, null, "interrupted", -1, false);
        } catch (Exception e) {
            log.error("执行命令失败: {} - {}", command, e.getMessage());
            return new CommandResult(false, null, e.getMessage(), -1, false);
        }
    }

    private static Charset commandOutputCharset() {
        if (OsRuntime.isWindows()) {
            return Charset.forName("GBK");
        }
        return StandardCharsets.UTF_8;
    }

    private static void drainStream(java.io.InputStream stream, StringBuilder target, int maxChars, String label) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, commandOutputCharset()))) {
            String line;
            int chars = 0;
            while ((line = reader.readLine()) != null) {
                int inc = line.length() + (target.length() > 0 ? 1 : 0);
                if (chars + inc > maxChars) {
                    if (target.length() > 0) {
                        target.append("\n");
                    }
                    target.append("...[").append(label).append(" truncated at ").append(maxChars).append(" chars]");
                    break;
                }
                chars += inc;
                if (target.length() > 0) {
                    target.append("\n");
                }
                target.append(line);
            }
        } catch (Exception e) {
            if (target.length() > 0) {
                target.append("\n");
            }
            target.append("...[").append(label).append(" read error: ").append(e.getMessage()).append("]");
        }
    }

    public record CommandResult(
        boolean success,
        String output,
        String error,
        int exitCode,
        boolean timeout
    ) {}
}
