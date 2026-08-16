package com.award.log.mapper;

import com.award.log.model.SysPermission;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统权限Mapper
 */
@Mapper
public interface SysPermissionMapper {
    /**
     * 根据权限ID查询权限
     * @param permissionId 权限ID
     * @return 权限信息
     */
    SysPermission selectById(Integer permissionId);

    /**
     * 根据权限代码查询权限
     * @param permissionCode 权限代码
     * @return 权限信息
     */
    SysPermission selectByPermissionCode(String permissionCode);

    /**
     * 查询所有权限
     * @return 权限列表
     */
    List<SysPermission> selectAll();

    /**
     * 分页查询权限列表
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 权限列表
     */
    List<SysPermission> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 查询权限总数
     * @return 权限总数
     */
    long countAll();

    /**
     * 插入权限
     * @param permission 权限信息
     * @return 影响行数
     */
    int insert(SysPermission permission);

    /**
     * 更新权限信息
     * @param permission 权限信息
     * @return 影响行数
     */
    int updateById(SysPermission permission);

    /**
     * 删除权限
     * @param permissionId 权限ID
     * @return 影响行数
     */
    int deleteById(Integer permissionId);

    /**
     * 根据角色ID查询权限列表
     * @param roleId 角色ID
     * @return 权限列表
     */
    List<SysPermission> selectByRoleId(Integer roleId);
}