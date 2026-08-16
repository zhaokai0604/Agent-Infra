package com.award.log.security.effect;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class EffectSecurityUpgradeTest {

    private final ToolEffectResolver resolver = new ToolEffectResolver();

    @Test
    void fingerprintIgnoresWriteToggleButTracksTargetChange() {
        ToolEffect effect = resolver.resolve("CleanTempTool", Map.of("path", "/tmp/a"));
        Map<String, Object> preview = new HashMap<>();
        preview.put("path", "/tmp/a");
        preview.put("dryRun", true);

        Map<String, Object> confirmed = new HashMap<>();
        confirmed.put("path", "/tmp/a");
        confirmed.put("dryRun", false);
        confirmed.put("confirmDelete", true);

        String fp1 = EffectFingerprint.of("CleanTempTool", preview, effect);
        String fp2 = EffectFingerprint.of("CleanTempTool", confirmed, effect);
        assertEquals(fp1, fp2);

        Map<String, Object> swapped = new HashMap<>();
        swapped.put("path", "/evil");
        swapped.put("dryRun", false);
        String fp3 = EffectFingerprint.of("CleanTempTool", swapped, effect);
        assertNotEquals(fp1, fp3);
    }

    @Test
    void evidenceContractRequiresDeleteFieldsOnRealWrite() {
        ToolEffect effect = resolver.resolve("CleanTempTool", Map.of("path", "/tmp/a"));
        String dryRun = "{\"success\":true,\"data\":{\"mode\":\"DRY-RUN\",\"path\":\"/tmp/a\"}}";
        assertTrue(EvidenceContractValidator.validateRequestedWrite(effect, true, dryRun).complete());

        String weakReal = "{\"success\":true,\"data\":{\"mode\":\"DELETE\",\"path\":\"/tmp/a\"}}";
        EvidenceContractValidator.ValidationResult weak =
                EvidenceContractValidator.validateRequestedWrite(effect, true, weakReal);
        assertFalse(weak.complete());

        String strongReal = "{\"success\":true,\"data\":{\"mode\":\"DELETE\",\"filesDeleted\":3,\"path\":\"/tmp/a\"}}";
        assertTrue(EvidenceContractValidator.validateRequestedWrite(effect, true, strongReal).complete());
    }

    @Test
    void sessionRiskBudgetBlocksAfterLimit() {
        SessionRiskBudgetService budget = new SessionRiskBudgetService(3_600_000L, 2, 100);
        ToolEffect effect = resolver.resolve("CleanTempTool", Map.of("path", "/tmp/a"));
        assertTrue(budget.check("u1", effect).allowed());
        budget.consume("u1", effect);
        budget.consume("u1", effect);
        SessionRiskBudgetService.BudgetDecision blocked = budget.check("u1", effect);
        assertFalse(blocked.allowed());
        assertEquals("RISK_BUDGET_WRITES", blocked.code());
    }

    @Test
    void capabilityTokenRejectsForgedId() {
        WriteCapabilityToken token = WriteCapabilityToken.issue("fp-1", "CleanTempTool", "u1", 60_000L);
        assertTrue(token.matches(token.tokenId(), "fp-1", "u1", System.currentTimeMillis()));
        assertFalse(token.matches("forged", "fp-1", "u1", System.currentTimeMillis()));
    }
}
