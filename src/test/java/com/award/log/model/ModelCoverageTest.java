package com.award.log.model;

import com.award.log.collector.model.RawLogEvent;
import com.award.log.dto.EnhancedLogParseResultEntity;
import com.award.log.dto.LogTemplateEntity;
import com.award.log.model.patrol.PatrolCorrelationSnapshot;
import com.award.log.model.patrol.PatrolFinding;
import com.award.log.support.PojoExerciseSupport;
import org.junit.jupiter.api.Test;

class ModelCoverageTest {

    @Test
    void exerciseModelAndDtoPojos() {
        PojoExerciseSupport.exerciseAll(
                AlarmRuleEntity.class,
                DecisionFeedback.class,
                DecisionLog.class,
                EngineOfflineMetric.class,
                EnhancedLogParseResultEntity.class,
                LogAlarm.class,
                LogAnalysisDetail.class,
                LogAnalysisTask.class,
                LogDocument.class,
                LogProtocolType.class,
                LogSeverityLevel.class,
                LogTemplateEntity.class,
                LogTemplateRecord.class,
                ModelEvaluation.class,
                PerformanceData.class,
                RuleDefinition.class,
                RuleHitStat.class,
                StorageLevel.class,
                SysPermission.class,
                SysRole.class,
                SysRolePermission.class,
                SysUser.class,
                SysUserRole.class,
                TaskAlarmConfig.class,
                TraceLog.class,
                UserApiKey.class,
                UserProfilePreference.class,
                PatrolFinding.class,
                PatrolCorrelationSnapshot.class,
                RawLogEvent.class
        );
    }
}
