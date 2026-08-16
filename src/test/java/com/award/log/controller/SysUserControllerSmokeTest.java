package com.award.log.controller;

import com.award.log.common.PageResult;
import com.award.log.model.SysUser;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.SysUserService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class SysUserControllerSmokeTest {

    @Mock private SysUserService sysUserService;
    @Mock private RequestUserResolver requestUserResolver;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        SysUserController controller = new SysUserController();
        ReflectionTestUtils.setField(controller, "sysUserService", sysUserService);
        ReflectionTestUtils.setField(controller, "requestUserResolver", requestUserResolver);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsUsers() throws Exception {
        when(sysUserService.getAllUsers()).thenReturn(List.of(new SysUser()));

        mockMvc.perform(get("/admin/user/list"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void pageReturnsPagedUsers() throws Exception {
        PageResult<SysUser> page = new PageResult<>();
        page.setList(List.of());
        page.setTotal(0);
        when(sysUserService.getUsersPage(anyInt(), anyInt())).thenReturn(page);

        mockMvc.perform(get("/admin/user/page"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.total").value(0));
    }
}
