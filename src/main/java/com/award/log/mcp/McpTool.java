package com.award.log.mcp;

import org.springframework.stereotype.Component;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记 MCP 工具 Bean，供 {@link McpToolCatalog} 自动登记 HTTP 白名单与默认风险分。
 * <p>
 * 未标注的 {@code com.award.log.mcp.tools.*Tool} 组件仍会被扫描注册（约定优于配置）。
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Component
public @interface McpTool {

    /** HTTP /api/mcp 使用的 toolName，默认 Bean 短类名 */
    String value() default "";

    /** 是否允许 HTTP 执行（对话 @Tool 仍可注入） */
    boolean httpAllowed() default true;

    /**
     * 默认工具基线风险分 0–10；&lt;0 表示由 {@link com.award.log.security.AgenticRiskScoreEngine} 回退计算
     */
    double defaultRiskScore() default -1;

    /** 只读观测类工具（用于意图与只读会话面校验） */
    boolean readOnlyObservation() default false;
}
