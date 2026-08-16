package com.award.log.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统权限模型
 */
@Data
public class SysPermission {
    private Integer permissionId;   // 权限ID
    private String permissionName;  // 权限名称
    private String permissionCode;  // 权限代码
    private LocalDateTime createTime; // 创建时间
}