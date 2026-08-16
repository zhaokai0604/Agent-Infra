package com.award.log.model;

import lombok.Data;

/**
 * 角色权限关联模型
 */
@Data
public class SysRolePermission {
    private Integer roleId;        // 角色ID
    private Integer permissionId;  // 权限ID
}