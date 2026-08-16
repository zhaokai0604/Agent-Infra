package com.award.log.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.time.LocalDateTime;

/**
 * 系统用户模型
 */
@Data
public class SysUser {
    private Integer userId;      // 用户ID
    private String username;     // 用户名
    private String password;     // 密码
    private Integer role;        // 角色：0-普通用户，1-管理员
    /** 企业微信用户ID；DB 列 wechat_userid，与 map-underscore-to-camel-case 对齐 */
    @JsonProperty("wechat_userid")
    private String wechatUserid;
    private String email;        // 邮箱地址，用于推送
    private LocalDateTime createTime; // 创建时间
    private LocalDateTime updateTime; // 更新时间
}