package com.award.log.security;

/**
 * {@code /admin/**} 路径的认证/授权分层。
 */
public final class AdminAccessPaths {

    private AdminAccessPaths() {
    }

    /** 无需登录：登录、注册、用户名占用检查 */
    public static boolean isPublic(String path) {
        return equalsPath(path, "/admin/user/login")
                || equalsPath(path, "/admin/user/register")
                || equalsPath(path, "/admin/user/check-user");
    }

    /** 已登录即可（任意角色）：登出、仪表盘只读统计 */
    public static boolean isAuthenticatedOnly(String path) {
        if (equalsPath(path, "/admin/user/logout")) {
            return true;
        }
        return path != null && path.startsWith("/admin/statistics/");
    }

    /** 其余 {@code /admin/**} 须管理员（role=1） */
    public static boolean requiresAdmin(String path) {
        if (path == null || !path.startsWith("/admin/")) {
            return false;
        }
        return !isPublic(path) && !isAuthenticatedOnly(path);
    }

    private static boolean equalsPath(String path, String expected) {
        return expected.equals(path);
    }
}
