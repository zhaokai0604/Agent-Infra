package com.award.log.security;

import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.reflect.MethodSignature;
import org.springframework.ai.tool.annotation.ToolParam;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.LinkedHashMap;
import java.util.Map;

/** 从 {@code @Tool} 方法调用提取参数名→值，供安全门与风险评分使用。 */
public final class ChatToolParamExtractor {

    private ChatToolParamExtractor() {
    }

    public static Map<String, Object> extract(ProceedingJoinPoint pjp) {
        Map<String, Object> map = new LinkedHashMap<>();
        if (!(pjp.getSignature() instanceof MethodSignature ms)) {
            return map;
        }
        Method method = ms.getMethod();
        Parameter[] params = method.getParameters();
        Object[] args = pjp.getArgs();
        if (params == null || args == null) {
            return map;
        }
        int n = Math.min(params.length, args.length);
        for (int i = 0; i < n; i++) {
            String key = paramName(params[i], i);
            map.put(key, args[i]);
        }
        return map;
    }

    private static String paramName(Parameter param, int index) {
        ToolParam ann = param.getAnnotation(ToolParam.class);
        if (ann != null && param.isNamePresent() && !param.getName().startsWith("arg")) {
            return param.getName();
        }
        if (param.isNamePresent() && !param.getName().startsWith("arg")) {
            return param.getName();
        }
        return "arg" + index;
    }
}
