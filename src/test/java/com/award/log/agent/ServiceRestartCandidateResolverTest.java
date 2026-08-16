package com.award.log.agent;

import com.award.log.config.AgentOpsProperties;
import com.award.log.governance.OpsGovernanceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class ServiceRestartCandidateResolverTest {

    private ServiceRestartCandidateResolver resolver;

    @BeforeEach
    void setUp() {
        OpsGovernanceService governance = new OpsGovernanceService(new com.award.log.governance.OpsGovernanceProperties());
        AgentOpsProperties props = new AgentOpsProperties();
        resolver = new ServiceRestartCandidateResolver(props, governance, new ObjectMapper());
    }

    @Test
    void pickFromFailedUnitsMatchesAllowlist() {
        String picked = resolver.pickFromFailedUnits(List.of("nginx.service", "sshd.service"));
        assertEquals("nginx", picked);
    }

    @Test
    void pickFromFailedUnitsSkipsForbidden() {
        assertNull(resolver.pickFromFailedUnits(List.of("sshd.service", "mysqld.service")));
    }

    @Test
    void parseFailedUnitsSkipsWindowsTableHeader() {
        String json = """
                {"success":true,"data":{"output":"Name Status StartType\\n---- ------ ---------\\nW32Time Stopped Automatic\\n"}}
                """;
        List<String> names = ServiceRestartCandidateResolver.parseFailedUnitsFromToolJson(json, new ObjectMapper());
        assertEquals(List.of("w32time"), names);
    }

    @Test
    void parseFailedUnitsFromSystemctlOutput() {
        String json = """
                {"success":true,"data":{"output":"nginx.service loaded failed failed A high performance web server\\n"}}
                """;
        List<String> names = ServiceRestartCandidateResolver.parseFailedUnitsFromToolJson(json, new ObjectMapper());
        assertEquals(List.of("nginx"), names);
    }
}
