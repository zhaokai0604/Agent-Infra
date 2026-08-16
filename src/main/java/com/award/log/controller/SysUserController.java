package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.model.SysUser;
import com.award.log.security.AuthInterceptor;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SysUserService;
import com.award.log.service.impl.AiAuditLogService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 系统用户管理接口。
 */
@Slf4j
@Tag(name = "System User")
@RestController
@RequestMapping("/admin/user")
public class SysUserController {

    @Autowired
    private SysUserService sysUserService;

    @Autowired
    private RequestUserResolver requestUserResolver;

    @Autowired(required = false)
    private AiAuditLogService aiAuditLogService;

    @Operation(summary = "用户登录")
    @PostMapping("/login")
    public Result<SysUser> login(@RequestBody SysUser loginUser,
                                 HttpSession session,
                                 HttpServletRequest request) {
        log.info("用户登录: {}", loginUser.getUsername());
        SysUser user = sysUserService.login(loginUser.getUsername(), loginUser.getPassword());
        if (user == null) {
            return Result.error("用户名或密码错误");
        }
        session.setAttribute(AuthInterceptor.SESSION_USER_ID, user.getUserId());
        session.setAttribute(AuthInterceptor.SESSION_USER_ROLE, user.getRole());
        if (aiAuditLogService != null) {
            aiAuditLogService.save(
                    user.getUserId(),
                    user.getRole(),
                    request.getRemoteAddr(),
                    "LOGIN",
                    request.getRequestURI(),
                    200,
                    0,
                    0);
        }
        user.setPassword(null);
        return Result.success(user);
    }

    @Operation(summary = "用户登出")
    @PostMapping("/logout")
    public Result<Boolean> logout(HttpSession session, HttpServletRequest request) {
        Object userId = session.getAttribute(AuthInterceptor.SESSION_USER_ID);
        Object role = session.getAttribute(AuthInterceptor.SESSION_USER_ROLE);
        if (aiAuditLogService != null && userId != null) {
            aiAuditLogService.save(
                    userId,
                    role,
                    request.getRemoteAddr(),
                    "LOGOUT",
                    request.getRequestURI(),
                    200,
                    0,
                    0);
        }
        session.invalidate();
        return Result.success(true);
    }

    @Operation(summary = "用户注册")
    @PostMapping("/register")
    public Result<Integer> register(@RequestBody SysUser user) {
        log.info("用户注册: {}", user.getUsername());
        if (user != null) {
            user.setRole(null);
        }
        Integer userId = sysUserService.registerUser(user);
        return userId != null
                ? Result.success(userId)
                : Result.error("用户名已存在或注册信息不完整");
    }

    @Operation(summary = "获取用户信息")
    @GetMapping("/{userId}")
    public Result<SysUser> getUserById(@PathVariable String userId) {
        log.info("获取用户信息: {}", userId);
        Integer id = parseId(userId);
        if (id == null) {
            return Result.error("用户ID格式错误");
        }
        SysUser user = sysUserService.getUserById(id);
        if (user != null) {
            user.setPassword(null);
        }
        return Result.success(user);
    }

    @Operation(summary = "更新用户信息")
    @PutMapping
    public Result<Boolean> updateUser(@RequestBody SysUser user) {
        log.info("更新用户信息: {}", user);
        if (user == null || user.getUserId() == null) {
            return Result.error("用户ID不能为空");
        }
        try {
            return Result.success(sysUserService.updateUser(user));
        } catch (Exception e) {
            log.error("更新用户信息失败", e);
            return Result.error("更新用户信息失败: " + e.getMessage());
        }
    }

    @Operation(summary = "删除用户")
    @DeleteMapping("/{userId}")
    public Result<Boolean> deleteUser(@PathVariable String userId) {
        log.info("删除用户: {}", userId);
        Integer id = parseId(userId);
        if (id == null) {
            return Result.error("用户ID格式错误");
        }
        return Result.success(sysUserService.deleteUser(id));
    }

    @Operation(summary = "获取全部用户")
    @GetMapping("/list")
    public Result<List<SysUser>> getAllUsers() {
        List<SysUser> users = sysUserService.getAllUsers();
        users.forEach(user -> user.setPassword(null));
        return Result.success(users);
    }

    @Operation(summary = "分页获取用户列表")
    @GetMapping("/page")
    public Result<PageResult<SysUser>> getUsersPage(@RequestParam(defaultValue = "1") int pageNum,
                                                    @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<SysUser> pageResult = sysUserService.getUsersPage(pageNum, pageSize);
        pageResult.getList().forEach(user -> user.setPassword(null));
        return Result.success(pageResult);
    }

    @Operation(summary = "检查用户是否存在")
    @PostMapping("/check-user")
    public Result<Boolean> checkUserExists(@RequestBody Map<String, String> userInfo) {
        return Result.success(sysUserService.userExists(userInfo.get("username")));
    }

    @Operation(summary = "重置密码（管理员）")
    @PostMapping("/reset-password")
    public Result<Boolean> resetPassword(@RequestBody Map<String, String> passwordInfo,
                                         HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "需要管理员权限");
        }
        String username = passwordInfo.get("username");
        String newPassword = passwordInfo.get("newPassword");
        if (username == null || username.isBlank() || newPassword == null || newPassword.isBlank()) {
            return Result.error("用户名与新密码不能为空");
        }
        boolean success = sysUserService.resetPassword(username, newPassword);
        return success ? Result.success(true) : Result.error("重置密码失败");
    }

    @Operation(summary = "更新用户角色")
    @PostMapping("/{userId}/role")
    public Result<Boolean> updateUserRole(@PathVariable String userId,
                                          @RequestBody Map<String, Integer> roleInfo,
                                          HttpServletRequest request) {
        if (!requestUserResolver.isAdmin(request)) {
            return Result.error(403, "需要管理员权限");
        }
        Integer id = parseId(userId);
        if (id == null) {
            return Result.error("用户ID格式错误");
        }
        Integer role = roleInfo.get("role");
        if (role == null) {
            return Result.error("角色信息不能为空");
        }
        try {
            boolean success = sysUserService.updateUserRole(id, role);
            return success ? Result.success(true) : Result.error("更新用户角色失败");
        } catch (Exception e) {
            log.error("更新用户角色失败: userId={}", userId, e);
            return Result.error("更新用户角色失败: " + e.getMessage());
        }
    }

    private static Integer parseId(String raw) {
        if (raw == null || raw.isBlank() || "null".equals(raw) || "undefined".equals(raw)) {
            return null;
        }
        try {
            return Integer.parseInt(raw);
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
