package com.award.log.service.impl;

import com.award.log.model.SysUser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.award.log.mapper.SysUserMapper;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysUserServiceImplSecurityTest {

    @Mock
    private SysUserMapper sysUserMapper;

    @InjectMocks
    private SysUserServiceImpl sysUserService;

    @BeforeEach
    void setUp() {
        lenient().when(sysUserMapper.selectByUsername("newbie")).thenReturn(null);
        lenient().when(sysUserMapper.insert(any(SysUser.class))).thenAnswer(inv -> {
            SysUser u = inv.getArgument(0);
            u.setUserId(99);
            return 1;
        });
    }

    @Test
    void registerUserAlwaysForcesOrdinaryRole() {
        SysUser user = new SysUser();
        user.setUsername("newbie");
        user.setPassword("Secret123!");
        user.setRole(1);

        Integer id = sysUserService.registerUser(user);

        assertEquals(99, id);
        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).insert(captor.capture());
        assertEquals(0, captor.getValue().getRole());
    }

    @Test
    void updateUserPreservesExistingRole() {
        SysUser existing = new SysUser();
        existing.setUserId(5);
        existing.setUsername("alice");
        existing.setRole(0);
        existing.setPassword("hash");
        when(sysUserMapper.selectById(5)).thenReturn(existing);
        when(sysUserMapper.updateById(any())).thenReturn(1);

        SysUser patch = new SysUser();
        patch.setUserId(5);
        patch.setUsername("alice");
        patch.setRole(1);

        assertTrue(sysUserService.updateUser(patch));

        ArgumentCaptor<SysUser> captor = ArgumentCaptor.forClass(SysUser.class);
        verify(sysUserMapper).updateById(captor.capture());
        assertEquals(0, captor.getValue().getRole());
    }
}
