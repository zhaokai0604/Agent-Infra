package com.award.log.service;

import com.award.log.common.PageResult;
import com.award.log.model.SysRole;

import java.util.List;

/**
 * 系统角色Service接口
 */
public interface SysRoleService {
    /**
     * 根据角色ID查询角色
     * @param roleId 角色ID
     * @return 角色信息
     */
    SysRole getRoleById(Integer roleId);

    /**
     * 根据角色名称查询角色
     * @param roleName 角色名称
     * @return 角色信息
     */
    SysRole getRoleByRoleName(String roleName);

    /**
     * 查询所有角色
     * @return 角色列表
     */
    List<SysRole> getAllRoles();

    /**
     * 分页查询角色列表
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 角色分页列表
     */
    PageResult<SysRole> getRolesPage(int pageNum, int pageSize);

    /**
     * 新增角色
     * @param role 角色信息
     * @return 角色ID
     */
    Integer addRole(SysRole role);

    /**
     * 更新角色
     * @param role 角色信息
     * @return 是否成功
     */
    boolean updateRole(SysRole role);

    /**
     * 删除角色
     * @param roleId 角色ID
     * @return 是否成功
     */
    boolean deleteRole(Integer roleId);
    
    /**
     * 保存角色权限分配
     * @param roleId 角色ID
     * @param permissionIds 权限ID列表
     * @return 是否成功
     */
    boolean saveRolePermissions(Integer roleId, List<Integer> permissionIds);
}
