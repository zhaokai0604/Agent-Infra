package com.award.log.security;

import com.award.log.config.ManagementPortProperties;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ManagementPortFilterTest {

    @Test
    void rejectsManagementRouteOnBusinessPort() throws Exception {
        ManagementPortFilter filter = new ManagementPortFilter(activeProperties(), 8088);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/award-log/api/mcp/tools");
        request.setContextPath("/award-log");
        request.setLocalPort(8088);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(403, response.getStatus());
    }

    @Test
    void rejectsBusinessRouteOnManagementPort() throws Exception {
        ManagementPortFilter filter = new ManagementPortFilter(activeProperties(), 8088);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/award-log/log/upload");
        request.setContextPath("/award-log");
        request.setLocalPort(8089);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(404, response.getStatus());
    }

    @Test
    void allowsMatchingPortAndRoute() throws Exception {
        ManagementPortFilter filter = new ManagementPortFilter(activeProperties(), 8088);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/award-log/api/mcp/tools");
        request.setContextPath("/award-log");
        request.setLocalPort(8089);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, new MockFilterChain());

        assertEquals(200, response.getStatus());
    }

    private static ManagementPortProperties activeProperties() {
        ManagementPortProperties props = new ManagementPortProperties();
        props.setEnabled(true);
        props.setPort(8089);
        return props;
    }
}
