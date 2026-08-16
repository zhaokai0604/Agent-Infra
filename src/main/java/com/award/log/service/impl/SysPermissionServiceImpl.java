package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysPermissionMapper;
import com.award.log.model.SysPermission;
import com.award.log.service.SysPermissionService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统权限Service实现
 */
@Slf4j
@Service
public class SysPermissionServiceImpl implements SysPermissionService {

    @Autowired
    private SysPermissionMapper sysPermissionMapper;

    @Override
    public SysPermission getPermissionById(Integer permissionId) {
        return sysPermissionMapper.selectById(permissionId);
    }

    @Override
    public SysPermission getPermissionByPermissionCode(String permissionCode) {
        return sysPermissionMapper.selectByPermissionCode(permissionCode);
    }

    @Override
    public List<SysPermission> getAllPermissions() {
        return sysPermissionMapper.selectAll();
    }

    @Override
    public PageResult<SysPermission> getPermissionsPage(int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        long total = sysPermissionMapper.countAll();
        List<SysPermission> permissions = sysPermissionMapper.selectPage(offset, pageSize);
        return new PageResult<>(permissions, total);
    }

    @Override
    public List<SysPermission> getPermissionsByRoleId(Integer roleId) {
        return sysPermissionMapper.selectByRoleId(roleId);
    }

    @Override
    public Integer addPermission(SysPermission permission) {
        // 检查权限代码是否已存在
        if (sysPermissionMapper.selectByPermissionCode(permission.getPermissionCode()) != null) {
            log.warn("权限代码已存在: {}", permission.getPermissionCode());
            return null;
        }

        int result = sysPermissionMapper.insert(permission);
        if (result > 0) {
            log.info("权限添加成功: {}", permission.getPermissionName());
            return permission.getPermissionId();
        } else {
            log.error("权限添加失败: {}", permission.getPermissionName());
            return null;
        }
    }

    @Override
    public boolean updatePermission(SysPermission permission) {
        int result = sysPermissionMapper.updateById(permission);
        boolean success = result > 0;
        if (success) {
            log.info("权限更新成功: {}", permission.getPermissionName());
        } else {
            log.error("权限更新失败: {}", permission.getPermissionName());
        }
        return success;
    }

    @Override
    public boolean deletePermission(Integer permissionId) {
        int result = sysPermissionMapper.deleteById(permissionId);
        boolean success = result > 0;
        if (success) {
            log.info("权限删除成功: {}", permissionId);
        } else {
            log.error("权限删除失败: {}", permissionId);
        }
        return success;
    }
}
