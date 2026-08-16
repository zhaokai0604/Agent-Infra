package com.award.log.controller;

import com.award.log.model.SysPermission;
import com.award.log.service.SysPermissionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SysPermissionControllerSmokeTest {

    @Mock private SysPermissionService sysPermissionService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SysPermissionController controller = new SysPermissionController();
        ReflectionTestUtils.setField(controller, "sysPermissionService", sysPermissionService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsPermissions() throws Exception {
        when(sysPermissionService.getAllPermissions()).thenReturn(List.of(new SysPermission()));

        mockMvc.perform(get("/admin/permission/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getPermissionByIdReturnsPermission() throws Exception {
        SysPermission permission = new SysPermission();
        permission.setPermissionId(1);
        permission.setPermissionCode("user:read");
        when(sysPermissionService.getPermissionById(1)).thenReturn(permission);

        mockMvc.perform(get("/admin/permission/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.permissionCode").value("user:read"));
    }
}
