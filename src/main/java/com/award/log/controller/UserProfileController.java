package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SysUserService;
import com.award.log.service.UserApiKeyService;
import com.award.log.service.UserProfilePreferenceService;
import com.award.log.service.impl.AiAuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 个人中心接口：仅允许访问当前会话用户自己的资料、偏好、密码和 API Key。
 */
@Slf4j
@Tag(name = "User Profile")
@RestController
@RequestMapping("/api/profile")
public class UserProfileController {

    private static final Pattern SIMPLE_EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");

    private final SysUserService sysUserService;
    private final UserProfilePreferenceService preferenceService;
    private final UserApiKeyService userApiKeyService;
    private final AiAuditLogService aiAuditLogService;
    private final RequestUserResolver requestUserResolver;

    public UserProfileController(SysUserService sysUserService,
                                 UserProfilePreferenceService preferenceService,
                                 UserApiKeyService userApiKeyService,
                                 AiAuditLogService aiAuditLogService,
                                 RequestUserResolver requestUserResolver) {
        this.sysUserService = sysUserService;
        this.preferenceService = preferenceService;
        this.userApiKeyService = userApiKeyService;
        this.aiAuditLogService = aiAuditLogService;
        this.requestUserResolver = requestUserResolver;
    }

    @Operation(summary = "获取当前登录用户资料")
    @GetMapping("/user-info")
    public Result<SysUser> getCurrentUserInfo(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        SysUser user = sysUserService.getUserById(userId);
        if (user == null) {
            return Result.error(404, "当前用户不存在");
        }
        return Result.success(sanitizeUser(user));
    }

    @Operation(summary = "更新当前登录用户资料")
    @PutMapping("/user-info")
    public Result<SysUser> updateUserInfo(HttpServletRequest request, @RequestBody(required = false) SysUser payload) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        if (payload == null) {
            return Result.error("请求体不能为空");
        }
        String email = normalizeNullable(payload.getEmail());
        String wechatUserid = normalizeNullable(payload.getWechatUserid());
        if (email != null && !SIMPLE_EMAIL.matcher(email).matches()) {
            return Result.error("邮箱格式不正确");
        }
        boolean updated = sysUserService.updateOwnProfile(userId, email, wechatUserid);
        if (!updated) {
            return Result.error("更新个人资料失败");
        }
        SysUser refreshed = sysUserService.getUserById(userId);
        return Result.success(sanitizeUser(refreshed), "个人资料已更新");
    }

    @Operation(summary = "修改当前登录用户密码")
    @PostMapping("/change-password")
    public Result<Boolean> changePassword(HttpServletRequest request,
                                          @RequestBody(required = false) Map<String, String> passwordInfo) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        if (passwordInfo == null) {
            return Result.error("请求体不能为空");
        }
        String oldPassword = trimToEmpty(passwordInfo.get("oldPassword"));
        String newPassword = trimToEmpty(passwordInfo.get("newPassword"));
        String confirmPassword = trimToEmpty(passwordInfo.get("confirmPassword"));
        if (oldPassword.isEmpty() || newPassword.isEmpty() || confirmPassword.isEmpty()) {
            return Result.error("密码字段不能为空");
        }
        if (!newPassword.equals(confirmPassword)) {
            return Result.error("两次输入的新密码不一致");
        }
        if (newPassword.length() < 8) {
            return Result.error("新密码长度不能少于 8 位");
        }
        if (newPassword.equals(oldPassword)) {
            return Result.error("新密码不能与旧密码相同");
        }
        boolean changed = sysUserService.changeOwnPassword(userId, oldPassword, newPassword);
        if (!changed) {
            return Result.error(400, "旧密码校验失败");
        }
        return Result.success(true, "密码已更新");
    }

    @Operation(summary = "获取当前用户通知偏好")
    @GetMapping("/notification-settings")
    public Result<Map<String, Object>> getNotificationSettings(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        return Result.success(preferenceService.getEffectivePreference(userId));
    }

    @Operation(summary = "保存当前用户通知偏好")
    @PutMapping("/notification-settings")
    public Result<Map<String, Object>> updateNotificationSettings(HttpServletRequest request,
                                                                  @RequestBody(required = false) Map<String, Object> body) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        Map<String, Object> payload = body == null ? Map.of() : body;
        return Result.success(preferenceService.savePreference(userId, payload), "通知偏好已保存");
    }

    @Operation(summary = "获取当前用户访问足迹")
    @GetMapping("/access-trail")
    public Result<Map<String, Object>> accessTrail(HttpServletRequest request,
                                                   @RequestParam(defaultValue = "1") int page,
                                                   @RequestParam(defaultValue = "10") int pageSize) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String subject = String.valueOf(userId);
        int safePage = Math.max(1, page);
        int safePageSize = Math.max(1, Math.min(pageSize, 50));
        long total = aiAuditLogService.countByUserId(subject);
        List<Map<String, Object>> items = aiAuditLogService.listRecentByUserId(subject, safePage, safePageSize);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("page", safePage);
        payload.put("pageSize", safePageSize);
        payload.put("total", total);
        payload.put("items", items);
        return Result.success(payload);
    }

    @Operation(summary = "兼容旧版登录历史接口")
    @GetMapping("/login-history")
    public Result<Map<String, Object>> loginHistoryAlias(HttpServletRequest request,
                                                         @RequestParam(defaultValue = "1") int page,
                                                         @RequestParam(defaultValue = "10") int pageSize) {
        return accessTrail(request, page, pageSize);
    }

    @Operation(summary = "获取当前用户 API Key 列表")
    @GetMapping("/api-keys")
    public Result<List<Map<String, Object>>> listApiKeys(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        return Result.success(userApiKeyService.listForUser(userId));
    }

    @Operation(summary = "创建当前用户 API Key")
    @PostMapping("/api-keys")
    public Result<Map<String, Object>> createApiKey(HttpServletRequest request,
                                                    @RequestBody(required = false) Map<String, Object> body) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        String name = body == null ? null : firstNonBlank(body.get("name"), body.get("keyName"));
        try {
            return Result.success(userApiKeyService.createForUser(userId, name), "API Key 已创建，仅展示一次完整明文");
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }

    @Operation(summary = "轮换当前用户 API Key")
    @PostMapping("/api-keys/{id}/rotate")
    public Result<Map<String, Object>> rotateApiKey(HttpServletRequest request, @PathVariable("id") Long id) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        try {
            return Result.success(userApiKeyService.rotateForUser(userId, id), "API Key 已轮换，仅展示一次完整明文");
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }

    @Operation(summary = "吊销当前用户 API Key")
    @PostMapping("/api-keys/{id}/revoke")
    public Result<Map<String, Object>> revokeApiKey(HttpServletRequest request, @PathVariable("id") Long id) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        try {
            return Result.success(userApiKeyService.revokeForUser(userId, id), "API Key 已吊销");
        } catch (IllegalArgumentException ex) {
            return Result.error(ex.getMessage());
        }
    }

    @Operation(summary = "兼容旧版生成 API Key 接口")
    @PostMapping("/generate-api-key")
    public Result<Map<String, Object>> generateApiKeyLegacy(HttpServletRequest request) {
        return createApiKey(request, Map.of("name", "Legacy API Key"));
    }

    @Operation(summary = "当前用户个人统计")
    @GetMapping("/user-stats")
    public Result<Map<String, Object>> userStats(HttpServletRequest request) {
        Integer userId = requestUserResolver.currentUserId(request);
        if (userId == null) {
            return unauthorized();
        }
        SysUser user = sysUserService.getUserById(userId);
        String subject = String.valueOf(userId);
        long accessTrailCount = aiAuditLogService.countByUserId(subject);
        List<Map<String, Object>> latest = aiAuditLogService.listRecentByUserId(subject, 1);
        long activeApiKeyCount = userApiKeyService.countActiveForUser(userId);
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("auditRequestCount", accessTrailCount);
        stats.put("accessTrailCount", accessTrailCount);
        stats.put("activeApiKeyCount", activeApiKeyCount);
        stats.put("hasApiKey", activeApiKeyCount > 0);
        stats.put("authMode", requestUserResolver.authMode(request));
        stats.put("lastActivityAt", latest.isEmpty() ? null : latest.get(0).get("created_at"));
        stats.put("username", user == null ? null : user.getUsername());
        stats.put("role", user == null ? null : user.getRole());
        return Result.success(stats);
    }

    private static SysUser sanitizeUser(SysUser user) {
        if (user == null) {
            return null;
        }
        user.setPassword(null);
        return user;
    }

    private static String normalizeNullable(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String trimToEmpty(String value) {
        return value == null ? "" : value.trim();
    }

    private static String firstNonBlank(Object first, Object second) {
        String candidate = first == null ? "" : String.valueOf(first).trim();
        if (!candidate.isEmpty()) {
            return candidate;
        }
        String fallback = second == null ? "" : String.valueOf(second).trim();
        return fallback.isEmpty() ? null : fallback;
    }

    private static <T> Result<T> unauthorized() {
        return Result.error(401, "未登录或登录已过期");
    }
}
