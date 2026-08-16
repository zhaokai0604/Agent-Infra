package com.award.log.mapper;

import com.award.log.model.SysRole;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 系统角色Mapper
 */
@Mapper
public interface SysRoleMapper {
    /**
     * 根据角色ID查询角色
     * @param roleId 角色ID
     * @return 角色信息
     */
    SysRole selectById(Integer roleId);

    /**
     * 根据角色名称查询角色
     * @param roleName 角色名称
     * @return 角色信息
     */
    SysRole selectByRoleName(String roleName);

    /**
     * 查询所有角色
     * @return 角色列表
     */
    List<SysRole> selectAll();

    /**
     * 分页查询角色列表
     * @param offset 偏移量
     * @param limit 每页条数
     * @return 角色列表
     */
    List<SysRole> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    /**
     * 查询角色总数
     * @return 角色总数
     */
    long countAll();

    /**
     * 插入角色
     * @param role 角色信息
     * @return 影响行数
     */
    int insert(SysRole role);

    /**
     * 更新角色信息
     * @param role 角色信息
     * @return 影响行数
     */
    int updateById(SysRole role);

    /**
     * 删除角色
     * @param roleId 角色ID
     * @return 影响行数
     */
    int deleteById(Integer roleId);
}