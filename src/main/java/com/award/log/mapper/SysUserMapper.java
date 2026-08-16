package com.award.log.mapper;

import com.award.log.model.SysUser;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统用户Mapper
 */
@Mapper
public interface SysUserMapper {
    /**
     * 根据用户ID查询用户
     * @param userId 用户ID
     * @return 用户信息
     */
    SysUser selectById(Integer userId);

    /**
     * 根据用户名查询用户
     * @param username 用户名
     * @return 用户信息
     */
    SysUser selectByUsername(String username);

    /**
     * 根据邮箱查询用户
     */
    SysUser selectByEmail(@Param("email") String email);

    /**
     * 查询所有用户
     * @return 用户列表
     */
    List<SysUser> selectAll();

    /**
     * 分页查询用户列表
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 用户列表
     */
    List<SysUser> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 查询用户总数
     * @return 用户总数
     */
    long countAll();

    /**
     * 插入用户
     * @param user 用户信息
     * @return 影响行数
     */
    int insert(SysUser user);

    /**
     * 更新用户信息
     * @param user 用户信息
     * @return 影响行数
     */
    int updateById(SysUser user);

    int updateProfileFields(@Param("userId") Integer userId,
                            @Param("email") String email,
                            @Param("wechatUserid") String wechatUserid);

    int updatePasswordById(@Param("userId") Integer userId,
                           @Param("password") String password);

    /**
     * 删除用户
     * @param userId 用户ID
     * @return 影响行数
     */
    int deleteById(Integer userId);
    
    /**
     * 查询管理员用户
     * @return 管理员用户列表
     */
    List<SysUser> selectAdminUsers();
}
