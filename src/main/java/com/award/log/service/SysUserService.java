package com.award.log.service;

import com.award.log.common.PageResult;
import com.award.log.model.SysUser;

import java.util.List;

/**
 * 系统用户Service
 */
public interface SysUserService {
    /**
     * 根据用户ID查询用户
     * @param userId 用户ID
     * @return 用户信息
     */
    SysUser getUserById(Integer userId);

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户信息
     */
    SysUser getUserByUsername(String username);

    /**
     * 根据邮箱查询用户（未设置邮箱的账号不会命中）
     */
    SysUser getUserByEmail(String email);

    /**
     * 查询所有用户
     * @return 用户列表
     */
    List<SysUser> getAllUsers();

    /**
     * 分页查询用户列表
     * @param pageNum 页码
     * @param pageSize 每页条数
     * @return 分页用户列表
     */
    PageResult<SysUser> getUsersPage(int pageNum, int pageSize);

    /**
     * 注册用户
     * @param user 用户信息
     * @return 用户ID
     */
    Integer registerUser(SysUser user);

    /**
     * 更新用户信息
     * @param user 用户信息
     * @return 是否成功
     */
    boolean updateUser(SysUser user);

    /**
     * 删除用户
     * @param userId 用户ID
     * @return 是否成功
     */
    boolean deleteUser(Integer userId);

    /**
     * 用户登录
     * @param username 用户名
     * @param password 密码
     * @return 用户信息，登录失败返回null
     */
    SysUser login(String username, String password);
    
    /**
     * 获取管理员用户
     * @return 管理员用户信息
     */
    SysUser getAdminUser();
    
    /**
     * 重置用户密码
     * @param username 用户名
     * @param newPassword 新密码
     * @return 是否成功
     */
    boolean resetPassword(String username, String newPassword);

    boolean matchesPassword(Integer userId, String rawPassword);

    boolean changeOwnPassword(Integer userId, String oldPassword, String newPassword);

    boolean updateOwnProfile(Integer userId, String email, String wechatUserid);
    
    /**
     * 根据用户名检查用户是否存在
     * @param username 用户名
     * @return 是否存在
     */
    boolean userExists(String username);
    
    /**
     * 更新用户角色
     * @param userId 用户ID
     * @param role 角色：0-普通用户，1-管理员
     * @return 是否成功
     */
    boolean updateUserRole(Integer userId, Integer role);
}
