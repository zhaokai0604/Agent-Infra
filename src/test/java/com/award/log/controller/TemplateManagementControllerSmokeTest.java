package com.award.log.controller;

import com.award.log.model.LogTemplateRecord;
import com.award.log.service.TemplateService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class TemplateManagementControllerSmokeTest {

    @Mock private TemplateService templateService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        TemplateManagementController controller = new TemplateManagementController(templateService);
        mockMvc = MockMvcBuilders.standaloneSetup(controller).build();
    }

    @Test
    void listReturnsTemplates() throws Exception {
        when(templateService.page(1, 20)).thenReturn(List.of(new LogTemplateRecord()));

        mockMvc.perform(get("/api/v1/templates"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data").isArray());
    }

    @Test
    void getReturnsTemplate() throws Exception {
        LogTemplateRecord record = new LogTemplateRecord();
        record.setTemplateId("t1");
        when(templateService.get("t1")).thenReturn(record);

        mockMvc.perform(get("/api/v1/templates/t1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(200))
                .andExpect(jsonPath("$.data.templateId").value("t1"));
    }
}
