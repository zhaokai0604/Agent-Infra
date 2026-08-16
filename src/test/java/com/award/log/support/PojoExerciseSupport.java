package com.award.log.support;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/**
 * Minimal POJO exercise helper for coverage tests.
 */
public final class PojoExerciseSupport {

    private PojoExerciseSupport() {
    }

    @SafeVarargs
    public static void exerciseAll(Class<?>... types) {
        for (Class<?> type : types) {
            exercise(type);
        }
    }

    public static void exercise(Class<?> type) {
        try {
            Object instance = newInstance(type);
            for (Method method : type.getMethods()) {
                if (method.getDeclaringClass() == Object.class) {
                    continue;
                }
                if (Modifier.isStatic(method.getModifiers())) {
                    continue;
                }
                String name = method.getName();
                if (method.getParameterCount() == 0 && method.getName().startsWith("get")) {
                    try {
                        method.invoke(instance);
                    } catch (Exception ignored) {
                    }
                }
                if (method.getParameterCount() == 1 && method.getName().startsWith("set")) {
                    try {
                        method.invoke(instance, defaultArg(method.getParameterTypes()[0]));
                    } catch (Exception ignored) {
                    }
                }
                if (method.getParameterCount() == 0 && ("toString".equals(name) || "hashCode".equals(name))) {
                    try {
                        method.invoke(instance);
                    } catch (Exception ignored) {
                    }
                }
                if (method.getParameterCount() == 1 && "equals".equals(name)) {
                    try {
                        method.invoke(instance, instance);
                        method.invoke(instance, new Object());
                    } catch (Exception ignored) {
                    }
                }
            }
            if (type.isEnum()) {
                Object[] constants = type.getEnumConstants();
                if (constants != null) {
                    for (Object constant : constants) {
                        ((Enum<?>) constant).name();
                    }
                }
            }
        } catch (Exception ignored) {
        }
    }

    private static Object newInstance(Class<?> type) throws Exception {
        if (type.isEnum()) {
            Object[] constants = type.getEnumConstants();
            return constants != null && constants.length > 0 ? constants[0] : null;
        }
        Constructor<?> ctor = type.getDeclaredConstructor();
        ctor.setAccessible(true);
        return ctor.newInstance();
    }

    private static Object defaultArg(Class<?> paramType) {
        if (!paramType.isPrimitive()) {
            return null;
        }
        if (paramType == boolean.class) {
            return false;
        }
        if (paramType == char.class) {
            return '\0';
        }
        if (paramType == byte.class) {
            return (byte) 0;
        }
        if (paramType == short.class) {
            return (short) 0;
        }
        if (paramType == int.class) {
            return 0;
        }
        if (paramType == long.class) {
            return 0L;
        }
        if (paramType == float.class) {
            return 0f;
        }
        if (paramType == double.class) {
            return 0d;
        }
        return null;
    }
}
