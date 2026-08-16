package com.award.log.service.impl;

import com.award.log.common.PageResult;
import com.award.log.mapper.SysPermissionMapper;
import com.award.log.model.SysPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SysPermissionServiceImplTest {

    @Mock
    private SysPermissionMapper sysPermissionMapper;

    private SysPermissionServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new SysPermissionServiceImpl();
        ReflectionTestUtils.setField(service, "sysPermissionMapper", sysPermissionMapper);
    }

    @Test
    void getPermissionByCodeShouldDelegateToMapper() {
        SysPermission permission = new SysPermission();
        permission.setPermissionCode("log:read");
        when(sysPermissionMapper.selectByPermissionCode("log:read")).thenReturn(permission);
        assertEquals(permission, service.getPermissionByPermissionCode("log:read"));
    }

    @Test
    void getPermissionsPageShouldReturnPagedResult() {
        when(sysPermissionMapper.countAll()).thenReturn(5L);
        when(sysPermissionMapper.selectPage(10, 10)).thenReturn(List.of(new SysPermission()));
        PageResult<SysPermission> page = service.getPermissionsPage(2, 10);
        assertEquals(5L, page.getTotal());
    }

    @Test
    void addPermissionShouldRejectDuplicateCode() {
        SysPermission permission = new SysPermission();
        permission.setPermissionCode("dup");
        when(sysPermissionMapper.selectByPermissionCode("dup")).thenReturn(new SysPermission());
        assertNull(service.addPermission(permission));
    }

    @Test
    void deletePermissionShouldReturnSuccessFlag() {
        when(sysPermissionMapper.deleteById(9)).thenReturn(1);
        assertTrue(service.deletePermission(9));
    }
}
