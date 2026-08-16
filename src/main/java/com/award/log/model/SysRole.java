package com.award.log.model;

import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统角色模型
 */
@Data
public class SysRole {
    private Integer roleId;      // 角色ID
    private String roleName;     // 角色名称
    private String roleDesc;     // 角色描述
    private LocalDateTime createTime; // 创建时间
}