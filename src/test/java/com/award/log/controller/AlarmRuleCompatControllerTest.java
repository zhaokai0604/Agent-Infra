package com.award.log.controller;

import com.award.log.common.Result;
import com.award.log.mapper.AlarmRuleMapper;
import com.award.log.model.AlarmRuleEntity;
import com.award.log.model.RuleDefinition;
import com.award.log.service.RuleRegistryService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AlarmRuleCompatControllerTest {

    @Mock
    private AlarmRuleMapper alarmRuleMapper;

    @Mock
    private RuleRegistryService ruleRegistryService;

    @InjectMocks
    private AlarmRuleCompatController controller;

    @Captor
    private ArgumentCaptor<RuleDefinition> ruleCaptor;

    @Test
    void add_acceptsCamelCaseRuleExpression() {
        RuleDefinition saved = new RuleDefinition();
        saved.setId("7");

        AlarmRuleEntity entity = new AlarmRuleEntity();
        entity.setId(7L);
        entity.setName("jar-rule");
        entity.setDescription("desc");
        entity.setRuleType("KEYWORD");
        entity.setRuleExpression("fatal,critical");
        entity.setSeverity("ERROR");
        entity.setPushChannels("BOTH");
        entity.setEnabled(true);
        entity.setCreateTime(LocalDateTime.now());
        entity.setUpdateTime(LocalDateTime.now());

        when(ruleRegistryService.save(ruleCaptor.capture())).thenReturn(saved);
        when(alarmRuleMapper.selectById(7L)).thenReturn(entity);

        Result<Map<String, Object>> result = controller.add(Map.of(
                "name", "jar-rule",
                "description", "desc",
                "ruleType", "KEYWORD",
                "ruleExpression", "fatal,critical",
                "severity", "ERROR",
                "pushChannels", "BOTH",
                "enabled", true
        ));

        RuleDefinition captured = ruleCaptor.getValue();
        assertEquals("fatal,critical", captured.getExpression());
        assertNotNull(result.getData());
        assertEquals(7L, result.getData().get("id"));
        verify(ruleRegistryService).save(ruleCaptor.getValue());
    }
}
