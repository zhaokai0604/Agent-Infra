package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysRoleMapper;
import com.award.log.mapper.SysRolePermissionMapper;
import com.award.log.model.SysRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysRoleServiceImplTest {

    @Mock
    private SysRoleMapper sysRoleMapper;
    @Mock
    private SysRolePermissionMapper sysRolePermissionMapper;

    private SysRoleServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysRoleServiceImpl();
        ReflectionTestUtils.setField(service, "sysRoleMapper", sysRoleMapper);
        ReflectionTestUtils.setField(service, "sysRolePermissionMapper", sysRolePermissionMapper);
    }

    @Test
    void getRoleByIdShouldDelegateToMapper() {
        SysRole role = new SysRole();
        role.setRoleId(1);
        when(sysRoleMapper.selectById(1)).thenReturn(role);
        assertEquals(role, service.getRoleById(1));
    }

    @Test
    void getRolesPageShouldCalculateOffset() {
        when(sysRoleMapper.countAll()).thenReturn(2L);
        when(sysRoleMapper.selectPage(0, 10)).thenReturn(List.of(new SysRole()));
        PageResult<SysRole> page = service.getRolesPage(1, 10);
        assertEquals(2L, page.getTotal());
        assertEquals(1, page.getList().size());
    }

    @Test
    void addRoleShouldRejectDuplicateName() {
        SysRole role = new SysRole();
        role.setRoleName("admin");
        when(sysRoleMapper.selectByRoleName("admin")).thenReturn(new SysRole());
        assertNull(service.addRole(role));
    }

    @Test
    void saveRolePermissionsShouldReplaceExisting() {
        when(sysRolePermissionMapper.deleteByRoleId(3)).thenReturn(1);
        when(sysRolePermissionMapper.batchInsert(anyList())).thenReturn(2);
        assertTrue(service.saveRolePermissions(3, List.of(10, 11)));
        verify(sysRolePermissionMapper).deleteByRoleId(3);
    }
}
