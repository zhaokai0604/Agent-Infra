package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysUserMapper;
import com.award.log.model.SysUser;
import com.award.log.service.SysUserService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * 系统用户Service实现
 */
@Slf4j
@Service
public class SysUserServiceImpl implements SysUserService {

    @Autowired
    private SysUserMapper sysUserMapper;

    // 密码加密器
    private final PasswordEncoder passwordEncoder = new BCryptPasswordEncoder();

    @Override
    public SysUser getUserById(Integer userId) {
        return sysUserMapper.selectById(userId);
    }

    @Override
    public SysUser getUserByUsername(String username) {
        return sysUserMapper.selectByUsername(username);
    }

    @Override
    public SysUser getUserByEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return sysUserMapper.selectByEmail(email.trim());
    }

    @Override
    public List<SysUser> getAllUsers() {
        return sysUserMapper.selectAll();
    }

    @Override
    public PageResult<SysUser> getUsersPage(int pageNum, int pageSize) {
        int offset = (pageNum - 1) * pageSize;
        long total = sysUserMapper.countAll();
        List<SysUser> users = sysUserMapper.selectPage(offset, pageSize);
        return new PageResult<>(users, total);
    }

    @Override
    public Integer registerUser(SysUser user) {
        // 检查用户名是否已存在
        if (sysUserMapper.selectByUsername(user.getUsername()) != null) {
            log.warn("用户名已存在: {}", user.getUsername());
            return null;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()
                && sysUserMapper.selectByEmail(user.getEmail().trim()) != null) {
            log.warn("邮箱已被注册: {}", user.getEmail());
            return null;
        }

        // 加密密码
        user.setPassword(passwordEncoder.encode(user.getPassword()));

        // 自注册一律为普通用户，忽略客户端传入的 role
        user.setRole(0);

        // 插入用户
        int result = sysUserMapper.insert(user);
        if (result > 0) {
            log.info("用户注册成功: {}", user.getUsername());
            return user.getUserId();
        } else {
            log.error("用户注册失败: {}", user.getUsername());
            return null;
        }
    }

    @Override
    public boolean updateUser(SysUser user) {
        if (user == null || user.getUserId() == null) {
            return false;
        }
        SysUser existing = sysUserMapper.selectById(user.getUserId());
        if (existing == null) {
            return false;
        }
        // 禁止通过通用更新接口篡改角色（须走 updateUserRole）
        user.setRole(existing.getRole());

        // 如果更新密码，需要加密
        if (user.getPassword() != null && !user.getPassword().isEmpty()) {
            user.setPassword(passwordEncoder.encode(user.getPassword()));
        } else {
            user.setPassword(existing.getPassword());
        }

        int result = sysUserMapper.updateById(user);
        boolean success = result > 0;
        if (success) {
            log.info("用户信息更新成功: {}", user.getUsername());
        } else {
            log.error("用户信息更新失败: {}", user.getUsername());
        }
        return success;
    }

    @Override
    public boolean deleteUser(Integer userId) {
        int result = sysUserMapper.deleteById(userId);
        boolean success = result > 0;
        if (success) {
            log.info("用户删除成功: {}", userId);
        } else {
            log.error("用户删除失败: {}", userId);
        }
        return success;
    }

    @Override
    public SysUser login(String username, String password) {
        // 根据用户名查询用户
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            log.warn("用户名不存在: {}", username);
            return null;
        }

        // 验证密码
        if (passwordEncoder.matches(password, user.getPassword())) {
            log.info("用户登录成功: {}", username);
            return user;
        } else {
            log.warn("密码错误: {}", username);
            return null;
        }
    }

    @Override
    public SysUser getAdminUser() {
        // 获取所有用户，找到角色为1的管理员用户
        List<SysUser> users = getAllUsers();
        for (SysUser user : users) {
            if (user != null && user.getRole() != null && user.getRole() == 1) {
                log.info("找到管理员用户: {}", user.getUsername());
                return user;
            }
        }
        log.warn("未找到管理员用户");
        return null;
    }

    @Override
    public boolean resetPassword(String username, String newPassword) {
        // 根据用户名查询用户
        SysUser user = sysUserMapper.selectByUsername(username);
        if (user == null) {
            log.warn("用户不存在: {}", username);
            return false;
        }

        // 加密新密码
        user.setPassword(passwordEncoder.encode(newPassword));

        // 更新用户信息
        int result = sysUserMapper.updateById(user);
        boolean success = result > 0;
        if (success) {
            log.info("用户密码重置成功: {}", username);
        } else {
            log.error("用户密码重置失败: {}", username);
        }
        return success;
    }

    @Override
    public boolean matchesPassword(Integer userId, String rawPassword) {
        if (userId == null || rawPassword == null) {
            return false;
        }
        SysUser user = sysUserMapper.selectById(userId);
        return user != null
                && user.getPassword() != null
                && passwordEncoder.matches(rawPassword, user.getPassword());
    }

    @Override
    public boolean changeOwnPassword(Integer userId, String oldPassword, String newPassword) {
        if (userId == null) {
            return false;
        }
        if (!matchesPassword(userId, oldPassword)) {
            return false;
        }
        String encoded = passwordEncoder.encode(newPassword);
        return sysUserMapper.updatePasswordById(userId, encoded) > 0;
    }

    @Override
    public boolean updateOwnProfile(Integer userId, String email, String wechatUserid) {
        if (userId == null) {
            return false;
        }
        return sysUserMapper.updateProfileFields(
                userId,
                email == null ? null : email.trim(),
                wechatUserid == null ? null : wechatUserid.trim()) > 0;
    }

    @Override
    public boolean userExists(String username) {
        SysUser user = sysUserMapper.selectByUsername(username);
        return user != null;
    }

    @Override
    public boolean updateUserRole(Integer userId, Integer role) {
        try {
            SysUser user = sysUserMapper.selectById(userId);
            if (user == null) {
                log.warn("用户不存在: {}", userId);
                return false;
            }
            user.setRole(role);
            int result = sysUserMapper.updateById(user);
            boolean success = result > 0;
            if (success) {
                log.info("用户角色更新成功: 用户ID={}, 角色={}", userId, role);
            } else {
                log.error("用户角色更新失败: 用户ID={}, 角色={}", userId, role);
            }
            return success;
        } catch (Exception e) {
            log.error("用户角色更新异常: 用户ID={}, 角色={}", userId, role, e);
            return false;
        }
    }
}
