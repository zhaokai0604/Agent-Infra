package com.award.log.actuator;

import com.award.log.service.EngineHealthService;
import org.springframework.boot.actuate.endpoint.annotation.Endpoint;
import org.springframework.boot.actuate.endpoint.annotation.ReadOperation;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
@Endpoint(id = "engine-health")
public class EngineHealthEndpoint {

    private final EngineHealthService engineHealthService;

    public EngineHealthEndpoint(EngineHealthService engineHealthService) {
        this.engineHealthService = engineHealthService;
    }

    @ReadOperation
    public Map<String, Object> health() {
        return engineHealthService.snapshot();
    }
}
