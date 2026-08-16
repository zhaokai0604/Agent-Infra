package com.award.log.service;

import com.award.log.common.PageResult;
import com.award.log.model.SysPermission;

import java.util.List;

/**
 * 系统权限Service接口
 */
public interface SysPermissionService {
    /**
     * 根据权限ID查询权限
     * @param permissionId 权限ID
     * @return 权限信息
     */
    SysPermission getPermissionById(Integer permissionId);

    /**
     * 根据权限代码查询权限
     * @param permissionCode 权限代码
     * @return 权限信息
     */
    SysPermission getPermissionByPermissionCode(String permissionCode);

    /**
     * 查询所有权限
     * @return 权限列表
     */
    List<SysPermission> getAllPermissions();

    /**
     * 分页查询权限列表
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 权限分页列表
     */
    PageResult<SysPermission> getPermissionsPage(int pageNum, int pageSize);

    /**
     * 根据角色ID查询权限列表
     * @param roleId 角色ID
     * @return 权限列表
     */
    List<SysPermission> getPermissionsByRoleId(Integer roleId);

    /**
     * 新增权限
     * @param permission 权限信息
     * @return 权限ID
     */
    Integer addPermission(SysPermission permission);

    /**
     * 更新权限
     * @param permission 权限信息
     * @return 是否成功
     */
    boolean updatePermission(SysPermission permission);

    /**
     * 删除权限
     * @param permissionId 权限ID
     * @return 是否成功
     */
    boolean deletePermission(Integer permissionId);
}
