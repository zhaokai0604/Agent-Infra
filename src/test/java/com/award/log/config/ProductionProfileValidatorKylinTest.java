package com.award.log.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

import static org.junit.jupiter.api.Assertions.*;

class ProductionProfileValidatorKylinTest {

    @Test
    void rejectsRelaxedAuditWhenKylinProfileActive() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "kylin");
        env.setProperty("spring.datasource.password", "secret");
        env.setProperty("app.security.ai-audit-relaxed-read", "true");

        ProductionProfileValidator validator = new ProductionProfileValidator(env);
        assertThrows(IllegalStateException.class,
                () -> validator.run(new DefaultApplicationArguments(new String[0])));
    }

    @Test
    void passesWithPasswordAndKylinProfile() {
        MockEnvironment env = new MockEnvironment();
        env.setActiveProfiles("prod", "kylin");
        env.setProperty("spring.datasource.password", "secret");
        env.setProperty("app.security.ai-audit-relaxed-read", "false");

        ProductionProfileValidator validator = new ProductionProfileValidator(env);
        assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments(new String[0])));
    }
}
