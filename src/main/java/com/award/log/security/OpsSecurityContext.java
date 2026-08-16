package com.award.log.security;



import lombok.Builder;

import lombok.Data;



/**

 * 线程内运维安全上下文：用于 AI 多步调 Tool 时做与 HTTP 层一致的安全校验。

 */

public final class OpsSecurityContext {



    private static final ThreadLocal<Ctx> HOLDER = new ThreadLocal<>();



    private OpsSecurityContext() {

    }



    public static void open(String traceId, String userMessage, boolean skipPerToolCheck) {

        open(traceId, userMessage, skipPerToolCheck, McpToolSurface.FULL);

    }



    public static void open(String traceId, String userMessage, boolean skipPerToolCheck, McpToolSurface toolSurface) {

        open(traceId, userMessage, skipPerToolCheck, toolSurface, false, false);

    }



    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface) {

        openChatAgent(traceId, userMessage, toolSurface, false);

    }



    public static void openChatAgent(String traceId, String userMessage, McpToolSurface toolSurface,

                                     boolean userConfirmedWrite) {

        open(traceId, userMessage, false, toolSurface, true, userConfirmedWrite);

    }



    public static void open(String traceId, String userMessage, boolean skipPerToolCheck,

                            McpToolSurface toolSurface, boolean chatAgentPath, boolean userConfirmedWrite) {

        HOLDER.set(Ctx.builder()

                .traceId(traceId)

                .userMessage(userMessage)

                .skipPerToolCheck(skipPerToolCheck)

                .toolSurface(toolSurface != null ? toolSurface : McpToolSurface.FULL)

                .chatAgentPath(chatAgentPath)

                .userConfirmedWrite(userConfirmedWrite)

                .build());

    }



    public static Ctx get() {

        return HOLDER.get();

    }



    public static void clear() {

        HOLDER.remove();

    }



    @Data

    @Builder

    public static class Ctx {

        private String traceId;

        private String userMessage;

        private boolean skipPerToolCheck;

        /** 工具面：AI 多步调用路径下用于收缩写类工具 */

        @Builder.Default

        private McpToolSurface toolSurface = McpToolSurface.FULL;

        /** 来自统一助手对话 ChatClient，写操作须走工具箱确认 */

        @Builder.Default

        private boolean chatAgentPath = false;

        /** HTTP MCP 二次确认通过后为 true（预留） */

        @Builder.Default

        private boolean userConfirmedWrite = false;

    }

}


