package com.award.log.config;

import com.award.log.util.OsRuntime;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Arrays;

/**
 * {@code spring.profiles.active} 含 {@code prod} 时，在启动早期强校验「不得空密钥跑生产」。
 * <p>数据库密码为空时直接失败；AI Key 为空时告警（允许纯离线部署，但助手/诊断将不可用）。</p>
 */
@Slf4j
@Component
@Profile("prod")
public class ProductionProfileValidator implements ApplicationRunner {

    private final Environment environment;

    public ProductionProfileValidator(Environment environment) {
        this.environment = environment;
    }

    @Override
    public void run(ApplicationArguments args) {
        String dbPassword = environment.getProperty("spring.datasource.password", "");
        if (!StringUtils.hasText(dbPassword)) {
            throw new IllegalStateException(
                    "[prod] 未配置数据库密码：请设置环境变量 DB_PASSWORD 或 spring.datasource.password（勿在仓库中提交明文）。");
        }
        String aiKey = environment.getProperty("spring.ai.openai.api-key", "");
        if (!StringUtils.hasText(aiKey)) {
            log.warn("[prod] 未配置 spring.ai.openai.api-key（AI_API_KEY）：统一助手、AI 诊断等将不可用。");
        }
        boolean kylinProfile = Arrays.stream(environment.getActiveProfiles())
                .anyMatch("kylin"::equals);
        if (OsRuntime.isUnixLike() && !kylinProfile) {
            log.warn("[prod] 当前为 Linux 但未激活 kylin profile；赛题交付（麒麟 V11 + LoongArch）请使用 "
                    + "--spring.profiles.active=prod-kylin");
        }
        if (kylinProfile) {
            if (!OsRuntime.isKylin()) {
                log.warn("[prod-kylin] 未检测到麒麟 OS 标识（os.name/os-release）；请确认运行在银河麒麟 V11 目标机");
            }
            if (!OsRuntime.isLoongArch()) {
                log.warn("[prod-kylin] 当前 CPU 架构为 {}，赛题目标为 LoongArch64", OsRuntime.osArch());
            }
            if (OsRuntime.isKylin() && OsRuntime.isLoongArch()) {
                log.info("[prod-kylin] 交付目标环境匹配：麒麟 + LoongArch64");
            }
            String relaxed = environment.getProperty("app.security.ai-audit-relaxed-read", "false");
            if ("true".equalsIgnoreCase(relaxed)) {
                throw new IllegalStateException(
                        "[prod-kylin] app.security.ai-audit-relaxed-read 不得为 true（生产审计必须收紧）");
            }
            String dryRunGlobal = environment.getProperty("ops.dry-run.global", "false");
            if ("true".equalsIgnoreCase(dryRunGlobal)) {
                log.warn("[prod-kylin] ops.dry-run.global=true：写操作全部为演练，真删/真重启不会落地");
            }
            String minPriv = environment.getProperty("agent.min-privilege.enabled", "false");
            if ("true".equalsIgnoreCase(minPriv)) {
                log.warn("[prod-kylin] agent.min-privilege.enabled=true：请确认 award-agent 用户与 sudoers 已配置，否则写操作易失败");
            }
            String profilesProp = environment.getProperty("spring.profiles.active", "");
            // 以 getActiveProfiles() 为准；Mock/容器注入可能不写 property 字符串
            boolean activeLooksProd = Arrays.stream(environment.getActiveProfiles())
                    .anyMatch(p -> "prod".equalsIgnoreCase(p) || "kylin".equalsIgnoreCase(p));
            if (!activeLooksProd && (!StringUtils.hasText(profilesProp) || "dev".equalsIgnoreCase(profilesProp.trim()))) {
                throw new IllegalStateException(
                        "[prod] 检测到生产 Profile 组下 spring.profiles.active 异常为 [" + profilesProp
                                + "]。麒麟交付请使用 prod-kylin，并确认 /etc/threshcore/threshcore.env 已加载。");
            }
        }
        log.info("[prod] 生产配置校验通过：已检测到非空 spring.datasource.password。");
    }
}
