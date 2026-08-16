package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysRoleMapper;
import com.award.log.mapper.SysRolePermissionMapper;
import com.award.log.model.SysRole;
import com.award.log.model.SysRolePermission;
import com.award.log.service.SysRoleService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

/**
 * 系统角色Service实现
 */
@Slf4j
@Service
public class SysRoleServiceImpl implements SysRoleService {

    @Autowired
    private SysRoleMapper sysRoleMapper;
    
    @Autowired
    private SysRolePermissionMapper sysRolePermissionMapper;

    @Override
    public SysRole getRoleById(Integer roleId) {
        return sysRoleMapper.selectById(roleId);
    }

    @Override
    public SysRole getRoleByRoleName(String roleName) {
        return sysRoleMapper.selectByRoleName(roleName);
    }

    @Override
    public List<SysRole> getAllRoles() {
        return sysRoleMapper.selectAll();
    }

    @Override
    public PageResult<SysRole> getRolesPage(int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        long total = sysRoleMapper.countAll();
        List<SysRole> roles = sysRoleMapper.selectPage(offset, pageSize);
        return new PageResult<>(roles, total);
    }

    @Override
    public Integer addRole(SysRole role) {
        // 检查角色名称是否已存在
        if (sysRoleMapper.selectByRoleName(role.getRoleName()) != null) {
            log.warn("角色名称已存在: {}", role.getRoleName());
            return null;
        }

        int result = sysRoleMapper.insert(role);
        if (result > 0) {
            log.info("角色添加成功: {}", role.getRoleName());
            return role.getRoleId();
        } else {
            log.error("角色添加失败: {}", role.getRoleName());
            return null;
        }
    }

    @Override
    public boolean updateRole(SysRole role) {
        int result = sysRoleMapper.updateById(role);
        boolean success = result > 0;
        if (success) {
            log.info("角色更新成功: {}", role.getRoleName());
        } else {
            log.error("角色更新失败: {}", role.getRoleName());
        }
        return success;
    }

    @Override
    public boolean deleteRole(Integer roleId) {
        int result = sysRoleMapper.deleteById(roleId);
        boolean success = result > 0;
        if (success) {
            log.info("角色删除成功: {}", roleId);
        } else {
            log.error("角色删除失败: {}", roleId);
        }
        return success;
    }

    @Override
    public boolean saveRolePermissions(Integer roleId, List<Integer> permissionIds) {
        try {
            // 先删除该角色现有的所有权限关联
            sysRolePermissionMapper.deleteByRoleId(roleId);
            
            // 然后为该角色创建新的权限关联
            if (permissionIds != null && !permissionIds.isEmpty()) {
                List<SysRolePermission> rolePermissions = permissionIds.stream()
                    .map(permissionId -> {
                        SysRolePermission rp = new SysRolePermission();
                        rp.setRoleId(roleId);
                        rp.setPermissionId(permissionId);
                        return rp;
                    })
                    .collect(Collectors.toList());
                
                sysRolePermissionMapper.batchInsert(rolePermissions);
            }
            
            log.info("角色权限分配保存成功: 角色ID={}, 权限数量={}", roleId, permissionIds != null ? permissionIds.size() : 0);
            return true;
        } catch (Exception e) {
            log.error("角色权限分配保存失败: 角色ID={}", roleId, e);
            return false;
        }
    }
}
