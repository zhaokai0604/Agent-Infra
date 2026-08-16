package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.security.RequestUserResolver;
import com.award.log.service.AgentPathPolicyService;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

class AgentPathPolicyControllerTest {

    @Test
    void nonAdminCannotReadPolicy() {
        AgentPathPolicyService service = Mockito.mock(AgentPathPolicyService.class);
        RequestUserResolver resolver = Mockito.mock(RequestUserResolver.class);
        HttpServletRequest request = Mockito.mock(HttpServletRequest.class);
        when(resolver.isAdmin(request)).thenReturn(false);
        AgentPathPolicyController controller = new AgentPathPolicyController(service, resolver);

        Result<Map<String, Object>> result = controller.getPolicy(request);

        assertEquals(403, result.getCode());
    }
}
