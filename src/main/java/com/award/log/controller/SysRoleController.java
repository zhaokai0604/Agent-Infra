package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.model.SysRole;
import com.award.log.service.SysRoleService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统角色控制器
 */
@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "System Role", description = "非默认交付面：RBAC 无 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/admin/role")
public class SysRoleController {

    @Autowired
    private SysRoleService sysRoleService;

    /**
     * 根据角色ID查询角色
     * @param roleId 角色ID
     * @return 角色信息
     */
    @GetMapping("/{roleId}")
    public Result<SysRole> getRoleById(@PathVariable Integer roleId) {
        SysRole role = sysRoleService.getRoleById(roleId);
        return Result.success(role);
    }

    /**
     * 根据角色名称查询角色
     * @param roleName 角色名称
     * @return 角色信息
     */
    @GetMapping("/name/{roleName}")
    public Result<SysRole> getRoleByRoleName(@PathVariable String roleName) {
        SysRole role = sysRoleService.getRoleByRoleName(roleName);
        return Result.success(role);
    }

    /**
     * 查询所有角色
     * @return 角色列表
     */
    @GetMapping("/list")
    public Result<List<SysRole>> getAllRoles() {
        List<SysRole> roles = sysRoleService.getAllRoles();
        return Result.success(roles);
    }

    /**
     * 分页查询角色列表
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 角色分页列表
     */
    @GetMapping("/page")
    public Result<PageResult<SysRole>> getRolesPage(@RequestParam(defaultValue = "1") int pageNum, 
                                                   @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<SysRole> pageResult = sysRoleService.getRolesPage(pageNum, pageSize);
        return Result.success(pageResult);
    }

    /**
     * 新增角色
     * @param role 角色信息
     * @return 角色ID
     */
    @PostMapping
    public Result<Integer> addRole(@RequestBody SysRole role) {
        log.info("新增角色: {}", role);
        try {
            if (role == null) {
                return Result.error("角色信息不能为空");
            }
            if (role.getRoleName() == null || role.getRoleName().trim().isEmpty()) {
                return Result.error("角色名称不能为空");
            }
            Integer roleId = sysRoleService.addRole(role);
            if (roleId != null) {
                return Result.success(roleId);
            } else {
                return Result.error("角色名称已存在");
            }
        } catch (Exception e) {
            log.error("新增角色失败: {}", role, e);
            return Result.error("新增角色失败: " + e.getMessage());
        }
    }

    /**
     * 更新角色
     * @param role 角色信息
     * @return 是否成功
     */
    @PutMapping
    public Result<Boolean> updateRole(@RequestBody SysRole role) {
        log.info("更新角色: {}", role);
        try {
            if (role == null) {
                return Result.error("角色信息不能为空");
            }
            if (role.getRoleId() == null) {
                return Result.error("角色ID不能为空");
            }
            if (role.getRoleName() == null || role.getRoleName().trim().isEmpty()) {
                return Result.error("角色名称不能为空");
            }
            boolean success = sysRoleService.updateRole(role);
            return Result.success(success);
        } catch (Exception e) {
            log.error("更新角色失败: {}", role, e);
            return Result.error("更新角色失败: " + e.getMessage());
        }
    }

    /**
     * 删除角色
     * @param roleId 角色ID
     * @return 是否成功
     */
    @DeleteMapping("/{roleId}")
    public Result<Boolean> deleteRole(@PathVariable String roleId) {
        log.info("删除角色: {}", roleId);
        try {
            // 处理"null"和"undefined"等特殊情况
            if ("null".equals(roleId) || "undefined".equals(roleId)) {
                return Result.error("角色ID不能为空");
            }
            Integer id = Integer.parseInt(roleId);
            boolean success = sysRoleService.deleteRole(id);
            return Result.success(success);
        } catch (NumberFormatException e) {
            log.error("角色ID格式错误: {}", roleId, e);
            return Result.error("角色ID格式错误");
        }
    }
    
    /**
     * 保存角色权限分配
     * @param roleId 角色ID
     * @param permissionIds 权限ID列表
     * @return 是否成功
     */
    @PostMapping("/{roleId}/permissions")
    public Result<Boolean> saveRolePermissions(@PathVariable String roleId, @RequestBody List<Integer> permissionIds) {
        log.info("保存角色权限分配: 角色ID={}, 权限ID列表={}", roleId, permissionIds);
        try {
            // 处理"null"和"undefined"等特殊情况
            if ("null".equals(roleId) || "undefined".equals(roleId)) {
                return Result.error("角色ID不能为空");
            }
            Integer id = Integer.parseInt(roleId);
            boolean success = sysRoleService.saveRolePermissions(id, permissionIds);
            return Result.success(success);
        } catch (NumberFormatException e) {
            log.error("角色ID格式错误: {}", roleId, e);
            return Result.error("角色ID格式错误");
        }
    }
}
