package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.common.Result;
import com.award.log.model.SysPermission;
import com.award.log.service.SysPermissionService;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 系统权限控制器
 */
@Slf4j
@Deprecated(since = "delivery-2026-07", forRemoval = false)
@Tag(name = "System Permission", description = "非默认交付面：RBAC 无 UI / 仅 API，见 docs/deployment/交付API白名单.md")
@RestController
@RequestMapping("/admin/permission")
public class SysPermissionController {

    @Autowired
    private SysPermissionService sysPermissionService;

    /**
     * 根据权限ID查询权限
     * @param permissionId 权限ID
     * @return 权限信息
     */
    @GetMapping("/{permissionId}")
    public Result<SysPermission> getPermissionById(@PathVariable Integer permissionId) {
        SysPermission permission = sysPermissionService.getPermissionById(permissionId);
        return Result.success(permission);
    }

    /**
     * 根据权限代码查询权限
     * @param permissionCode 权限代码
     * @return 权限信息
     */
    @GetMapping("/code/{permissionCode}")
    public Result<SysPermission> getPermissionByPermissionCode(@PathVariable String permissionCode) {
        SysPermission permission = sysPermissionService.getPermissionByPermissionCode(permissionCode);
        return Result.success(permission);
    }

    /**
     * 查询所有权限
     * @return 权限列表
     */
    @GetMapping("/list")
    public Result<List<SysPermission>> getAllPermissions() {
        List<SysPermission> permissions = sysPermissionService.getAllPermissions();
        return Result.success(permissions);
    }

    /**
     * 分页查询权限列表
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 权限分页列表
     */
    @GetMapping("/page")
    public Result<PageResult<SysPermission>> getPermissionsPage(@RequestParam(defaultValue = "1") int pageNum, 
                                                               @RequestParam(defaultValue = "10") int pageSize) {
        PageResult<SysPermission> pageResult = sysPermissionService.getPermissionsPage(pageNum, pageSize);
        return Result.success(pageResult);
    }

    /**
     * 根据角色ID查询权限列表
     * @param roleId 角色ID
     * @return 权限列表
     */
    @GetMapping("/role/{roleId}")
    public Result<List<SysPermission>> getPermissionsByRoleId(@PathVariable Integer roleId) {
        List<SysPermission> permissions = sysPermissionService.getPermissionsByRoleId(roleId);
        return Result.success(permissions);
    }

    /**
     * 新增权限
     * @param permission 权限信息
     * @return 权限ID
     */
    @PostMapping
    public Result<Integer> addPermission(@RequestBody SysPermission permission) {
        Integer permissionId = sysPermissionService.addPermission(permission);
        if (permissionId != null) {
            return Result.success(permissionId);
        } else {
            return Result.error("权限代码已存在");
        }
    }

    /**
     * 更新权限
     * @param permission 权限信息
     * @return 是否成功
     */
    @PutMapping
    public Result<Boolean> updatePermission(@RequestBody SysPermission permission) {
        boolean success = sysPermissionService.updatePermission(permission);
        return Result.success(success);
    }

    /**
     * 删除权限
     * @param permissionId 权限ID
     * @return 是否成功
     */
    @DeleteMapping("/{permissionId}")
    public Result<Boolean> deletePermission(@PathVariable Integer permissionId) {
        boolean success = sysPermissionService.deletePermission(permissionId);
        return Result.success(success);
    }
}
