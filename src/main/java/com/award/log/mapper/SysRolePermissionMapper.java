package com.award.log.mapper;

import com.award.log.model.SysRolePermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 角色权限关联Mapper
 */
@Mapper
public interface SysRolePermissionMapper {
    /**
     * 根据角色ID查询角色权限关联列表
     * @param roleId 角色ID
     * @return 角色权限关联列表
     */
    List<SysRolePermission> selectByRoleId(Integer roleId);

    /**
     * 根据权限ID查询角色权限关联列表
     * @param permissionId 权限ID
     * @return 角色权限关联列表
     */
    List<SysRolePermission> selectByPermissionId(Integer permissionId);

    /**
     * 插入角色权限关联
     * @param rolePermission 角色权限关联信息
     * @return 影响行数
     */
    int insert(SysRolePermission rolePermission);

    /**
     * 批量插入角色权限关联
     * @param rolePermissions 角色权限关联列表
     * @return 影响行数
     */
    int batchInsert(@Param("rolePermissions") List<SysRolePermission> rolePermissions);

    /**
     * 删除角色权限关联
     * @param roleId 角色ID
     * @param permissionId 权限ID
     * @return 影响行数
     */
    int deleteByRoleIdAndPermissionId(@Param("roleId") Integer roleId, @Param("permissionId") Integer permissionId);

    /**
     * 根据角色ID删除角色权限关联
     * @param roleId 角色ID
     * @return 影响行数
     */
    int deleteByRoleId(Integer roleId);

    /**
     * 根据权限ID删除角色权限关联
     * @param permissionId 权限ID
     * @return 影响行数
     */
    int deleteByPermissionId(Integer permissionId);
}