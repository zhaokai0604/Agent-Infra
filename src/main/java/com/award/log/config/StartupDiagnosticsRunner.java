package com.award.log.config;

import com.award.log.service.OpsPatrolService;
import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.env.Environment;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.concurrent.CompletableFuture;

/**
 * 启动后打一条固定前缀的日志，便于确认「后端真的起来了」以及邮件/本地配置是否生效。
 * 日志同时受 log4j2.xml 控制：控制台 + logs/award-log.log（相对 JVM 工作目录）。
 */
@Slf4j
@Component
public class StartupDiagnosticsRunner implements ApplicationRunner {

    private final Environment environment;
    private final ResourceLoader resourceLoader;

    @Autowired(required = false)
    private OpsPatrolService opsPatrolService;

    @Value("${ops.patrol.enabled:true}")
    private boolean patrolEnabled;

    @Value("${ops.patrol.warm-on-startup:true}")
    private boolean patrolWarmOnStartup;

    public StartupDiagnosticsRunner(Environment environment, ResourceLoader resourceLoader) {
        this.environment = environment;
        this.resourceLoader = resourceLoader;
    }

    @Override
    public void run(ApplicationArguments args) {
        Resource local = resourceLoader.getResource("classpath:application-local.yml");
        boolean localPresent = local.exists();
        String active = String.join(",", environment.getActiveProfiles());
        if (!StringUtils.hasText(active)) {
            active = "(默认)";
        }

        String dbPass = environment.getProperty("spring.datasource.password", "");
        boolean dbPassOk = StringUtils.hasText(dbPass);
        String localConfigPath = environment.getProperty("award.local-config.path", "");
        String localConfigLoaded = environment.getProperty("award.local-config.loaded", "false");
        String aiApiKey = environment.getProperty("spring.ai.openai.api-key", "");
        boolean aiKeyOk = StringUtils.hasText(aiApiKey);

        log.info("========== [ThreshCore] 后端已就绪 ==========");
        log.info("[ThreshCore] activeProfiles={}", active);
        log.info("[ThreshCore] platform os={} arch={} kylin={} loongArch={} deliveryMatch={}",
                OsRuntime.osName(), OsRuntime.osArch(), OsRuntime.isKylin(), OsRuntime.isLoongArch(),
                OsRuntime.isKylin() && OsRuntime.isLoongArch());
        log.info("[ThreshCore] user.dir={}", System.getProperty("user.dir"));
        log.info("[ThreshCore] system config dir={}", SystemConfigFileSupport.activeConfigDir());
        log.info("[ThreshCore] overrideFile={} exists={}",
                SystemConfigFileSupport.overrideFile().toAbsolutePath(),
                SystemConfigFileSupport.overrideFile().toFile().exists());
        log.info("[ThreshCore] secretFile={} exists={}",
                SystemConfigFileSupport.secretFile().toAbsolutePath(),
                SystemConfigFileSupport.secretFile().toFile().exists());
        log.info("[ThreshCore] pathPolicyFile={} exists={}",
                SystemConfigFileSupport.pathPolicyFile().toAbsolutePath(),
                SystemConfigFileSupport.pathPolicyFile().toFile().exists());
        log.info("[ThreshCore] classpath:application-local.yml 是否存在={}", localPresent);
        log.info("[ThreshCore] external application-local.yml loaded={} path={}",
                localConfigLoaded,
                StringUtils.hasText(localConfigPath) ? localConfigPath : "(not found)");
        log.info("[ThreshCore] spring.ai.openai.api-key configured={} (secret is not printed)", aiKeyOk);
        log.info("[ThreshCore] spring.datasource.password 已配置={}（未配置时 JDBC 将失败；开发请用 application-local.yml 或 DB_PASSWORD）", dbPassOk);
        log.info("[ThreshCore] 日志：本控制台 + 相对启动目录下 logs/award-log.log");
        log.info("==============================================");

        if (opsPatrolService != null && patrolEnabled && patrolWarmOnStartup) {
            CompletableFuture.runAsync(() -> {
                try {
                    Thread.sleep(2500);
                    opsPatrolService.runPatrolCycle();
                    log.info("[ThreshCore] 运维巡检：启动预热已执行一轮（correlation / 顶栏快照）");
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                } catch (Exception ex) {
                    log.warn("[ThreshCore] 运维巡检启动预热失败: {}", ex.getMessage());
                }
            });
        }
    }
}
