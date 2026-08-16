package com.award.log.controller;

import com.award.log.model.SysRole;
import com.award.log.service.SysRoleService;
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
class SysRoleControllerSmokeTest {

    @Mock private SysRoleService sysRoleService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SysRoleController controller = new SysRoleController();
        ReflectionTestUtils.setField(controller, "sysRoleService", sysRoleService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsRoles() throws Exception {
        when(sysRoleService.getAllRoles()).thenReturn(List.of(new SysRole()));

        mockMvc.perform(get("/admin/role/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getRoleByIdReturnsRole() throws Exception {
        SysRole role = new SysRole();
        role.setRoleId(1);
        role.setRoleName("admin");
        when(sysRoleService.getRoleById(1)).thenReturn(role);

        mockMvc.perform(get("/admin/role/1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.roleName").value("admin"));
    }
}
