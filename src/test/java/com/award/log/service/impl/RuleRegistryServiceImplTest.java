package com.award.log.service.impl;

import com.award.log.mapper.AlarmRuleMapper;
import com.award.log.model.AlarmRuleEntity;
import com.award.log.model.RuleDefinition;
import com.award.log.rule.dsl.RuleExpressionEvaluator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class RuleRegistryServiceImplTest {

    @Mock
    private RuleExpressionEvaluator expressionEvaluator;
    @Mock
    private AlarmRuleMapper alarmRuleMapper;

    private RuleRegistryServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new RuleRegistryServiceImpl(expressionEvaluator, alarmRuleMapper);
        lenient().when(expressionEvaluator.evaluate(anyString(), anyMap())).thenReturn(true);
    }

    @Test
    void initShouldLoadRulesFromDatabase() {
        AlarmRuleEntity entity = sampleEntity(1L, "disk-high", "LEVEL IN (ERROR) AND NOT SUPPRESSED");
        when(alarmRuleMapper.selectAll()).thenReturn(List.of(entity));

        service.init();

        List<RuleDefinition> rules = service.list();
        assertEquals(1, rules.size());
        assertEquals("disk-high", rules.get(0).getName());
    }

    @Test
    void saveShouldInsertNewRuleAndCacheIt() {
        when(alarmRuleMapper.selectAll()).thenReturn(List.of());
        service.init();

        RuleDefinition rule = new RuleDefinition();
        rule.setName("new-rule");
        rule.setExpression("LEVEL IN (ERROR) AND RATE > 0.1");
        doAnswer(inv -> {
            AlarmRuleEntity e = inv.getArgument(0);
            e.setId(42L);
            return 1;
        }).when(alarmRuleMapper).insert(any());

        RuleDefinition saved = service.save(rule);

        assertEquals("42", saved.getId());
        assertEquals("new-rule", saved.getName());
        assertTrue(service.list().stream().anyMatch(r -> "42".equals(r.getId())));
    }

    @Test
    void saveShouldRejectInvalidRule() {
        when(alarmRuleMapper.selectAll()).thenReturn(List.of());
        service.init();

        RuleDefinition blank = new RuleDefinition();
        blank.setName(" ");
        assertThrows(IllegalArgumentException.class, () -> service.save(blank));
    }

    @Test
    void saveShouldUpdateExistingRule() {
        AlarmRuleEntity existing = sampleEntity(7L, "old", "LEVEL IN (ERROR)");
        when(alarmRuleMapper.selectAll()).thenReturn(List.of(existing));
        service.init();
        when(alarmRuleMapper.update(any())).thenReturn(1);

        RuleDefinition update = new RuleDefinition();
        update.setId("7");
        update.setName("updated");
        update.setExpression("COUNT > 5");

        RuleDefinition saved = service.save(update);
        assertEquals("updated", saved.getName());
        verify(alarmRuleMapper).update(any());
    }

    @Test
    void deleteShouldRemoveFromCacheWhenDbSucceeds() {
        AlarmRuleEntity entity = sampleEntity(3L, "to-delete", "LEVEL IN (ERROR)");
        when(alarmRuleMapper.selectAll()).thenReturn(new ArrayList<>(List.of(entity)));
        service.init();
        when(alarmRuleMapper.deleteById(3L)).thenReturn(1);

        assertTrue(service.delete("3"));
        assertTrue(service.list().isEmpty());
    }

    @Test
    void deleteReturnsFalseForInvalidId() {
        when(alarmRuleMapper.selectAll()).thenReturn(List.of());
        service.init();
        assertFalse(service.delete("not-a-number"));
    }

    @Test
    void testRuleShouldEvaluateExpressionWhenEnabled() {
        AlarmRuleEntity entity = sampleEntity(5L, "enabled", "LEVEL IN (ERROR)");
        when(alarmRuleMapper.selectAll()).thenReturn(List.of(entity));
        service.init();
        when(expressionEvaluator.evaluate(eq("LEVEL IN (ERROR)"), anyMap())).thenReturn(true);

        assertTrue(service.testRule("5", Map.of("LEVEL", "ERROR")));
    }

    @Test
    void testRuleReturnsFalseWhenDisabled() {
        AlarmRuleEntity entity = sampleEntity(6L, "disabled", "LEVEL IN (ERROR)");
        entity.setEnabled(false);
        when(alarmRuleMapper.selectAll()).thenReturn(List.of(entity));
        service.init();

        assertFalse(service.testRule("6", Map.of("LEVEL", "ERROR")));
    }

    private static AlarmRuleEntity sampleEntity(long id, String name, String expression) {
        AlarmRuleEntity entity = new AlarmRuleEntity();
        entity.setId(id);
        entity.setName(name);
        entity.setDescription(name);
        entity.setRuleType("COMBINATION");
        entity.setRuleExpression(expression);
        entity.setSeverity("ERROR");
        entity.setPushChannels("BOTH");
        entity.setEnabled(true);
        return entity;
    }
}
