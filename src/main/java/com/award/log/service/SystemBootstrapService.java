package com.award.log.service;

import com.award.log.config.SystemBootstrapSupport;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Bean wrapper for startup bootstrap reconciliation so controllers can trigger it safely.
 */
@Service
public class SystemBootstrapService {

    private final AgentPathPolicyService agentPathPolicyService;
    private final SystemConfigService systemConfigService;

    public SystemBootstrapService(AgentPathPolicyService agentPathPolicyService,
                                  SystemConfigService systemConfigService) {
        this.agentPathPolicyService = agentPathPolicyService;
        this.systemConfigService = systemConfigService;
    }

    public Map<String, Object> getStatus() {
        return new LinkedHashMap<>(SystemBootstrapSupport.readBootstrapStatus());
    }

    public Map<String, Object> reconcileNow() {
        Map<String, Object> status = new LinkedHashMap<>(
                SystemBootstrapSupport.reconcileForCurrentPlatform("manual-reconcile"));
        agentPathPolicyService.applyOverridesFromFileIfPresent();
        systemConfigService.reapplySavedRuntimeConfig();
        return status;
    }
}
