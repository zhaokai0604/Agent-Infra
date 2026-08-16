package com.award.log.service;

import com.award.log.model.LogAlarm;

import java.util.List;
import java.util.Map;

/**
 * AI日志告警服务接口
 * 负责AI识别异常日志、生成结构化告警信息、多渠道推送
 */
public interface AiLogAlarmService {
    
    /**
     * 分析日志并生成告警
     * @param taskId 日志分析任务ID
     * @param logContent 日志内容
     * @return 生成的告警信息
     */
    LogAlarm analyzeLogAndGenerateAlarm(String taskId, String logContent);
    
    /**
     * 推送告警
     * @param alarm 告警信息
     * @return 推送结果
     */
    boolean pushAlarm(LogAlarm alarm);
    
    /**
     * 重试推送告警
     * @param alarmId 告警ID
     * @param retryCount 重试次数
     * @return 推送结果
     */
    boolean retryPushAlarm(Integer alarmId, int retryCount);
    
    /**
     * 根据任务ID处理告警
     * @param taskId 日志分析任务ID
     */
    void processAlarmsByTaskId(String taskId);

    void processAlarmsByTaskIdForUser(String taskId, Integer userId, boolean admin);

    /**
     * 获取告警历史列表
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param level 告警级别筛选
     * @param taskId 任务ID筛选
     * @return 告警历史列表及分页信息
     */
    Map<String, Object> getAlarmHistory(int pageNum, int pageSize, String level, String taskId);

    Map<String, Object> getAlarmHistoryForUser(int pageNum, int pageSize, String level, String taskId,
                                               Integer userId, boolean admin);

    /**
     * 获取告警统计分析（可选按级别、任务筛选，与列表筛选口径一致）
     */
    Map<String, Object> getAlarmStatistics(int days, String level, String taskId);

    Map<String, Object> getAlarmStatisticsForUser(int days, String level, String taskId,
                                                  Integer userId, boolean admin);

    List<Map<String, Object>> getAlarmTrend(int days, String level, String taskId);

    List<Map<String, Object>> getAlarmTrendForUser(int days, String level, String taskId,
                                                   Integer userId, boolean admin);

    List<Map<String, Object>> getAlarmLevelDistribution(int days, String level, String taskId);

    List<Map<String, Object>> getAlarmLevelDistributionForUser(int days, String level, String taskId,
                                                               Integer userId, boolean admin);

    List<Map<String, Object>> getAlarmRootCauseStatistics(int days, String level, String taskId);

    List<Map<String, Object>> getAlarmRootCauseStatisticsForUser(int days, String level, String taskId,
                                                                 Integer userId, boolean admin);
}
