package com.award.log.mapper;

import com.award.log.model.LogAlarm;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

/**
 * 日志告警Mapper接口
 * 用于操作log_alarm表
 */
@Mapper
public interface LogAlarmMapper {

    /**
     * 插入告警记录
     * @param alarm 告警信息
     * @return 插入结果
     */
    int insert(LogAlarm alarm);

    /**
     * 根据ID查询告警
     * @param id 告警ID
     * @return 告警信息
     */
    LogAlarm selectById(Long id);

    /**
     * 根据告警ID查询告警
     * @param alarmId 告警唯一标识
     * @return 告警信息
     */
    LogAlarm selectByAlarmId(String alarmId);

    LogAlarm selectLatestByFingerprint(@Param("taskId") String taskId,
                                       @Param("level") String level,
                                       @Param("rootCausePrefix") String rootCausePrefix,
                                       @Param("minutes") int minutes);

    /**
     * 根据任务ID查询告警列表
     * @param taskId 任务ID
     * @return 告警列表
     */
    List<LogAlarm> selectByTaskId(String taskId);

    /**
     * 更新告警信息
     * @param alarm 告警信息
     * @return 更新结果
     */
    int update(LogAlarm alarm);

    /**
     * 更新告警推送状态
     * @param alarmId 告警唯一标识
     * @param pushStatus 推送状态
     * @return 更新结果
     */
    int updatePushStatus(@Param("alarmId") String alarmId, @Param("pushStatus") String pushStatus);

    int updateLifecycle(@Param("alarmId") String alarmId,
                        @Param("lifecycleStatus") String lifecycleStatus,
                        @Param("operator") String operator);

    int increaseEscalation(@Param("alarmId") String alarmId);

    List<LogAlarm> selectNeedEscalation(@Param("minutes") int minutes);

    /**
     * 分页查询告警历史
     * @param pageNum 页码
     * @param pageSize 每页大小
     * @param offset 偏移量
     * @param level 告警级别筛选
     * @param taskId 任务ID筛选
     * @return 告警列表
     */
    List<LogAlarm> selectPage(@Param("pageNum") int pageNum, @Param("pageSize") int pageSize, 
                             @Param("offset") int offset, @Param("level") String level, @Param("taskId") String taskId);

    List<LogAlarm> selectPageByUserId(@Param("pageSize") int pageSize,
                                      @Param("offset") int offset,
                                      @Param("userId") Integer userId,
                                      @Param("level") String level,
                                      @Param("taskId") String taskId);

    /**
     * 查询告警总数
     * @param level 告警级别筛选
     * @param taskId 任务ID筛选
     * @return 告警总数
     */
    long count(@Param("level") String level, @Param("taskId") String taskId);

    long countByUserId(@Param("userId") Integer userId,
                       @Param("level") String level,
                       @Param("taskId") String taskId);

    /**
     * 查询最近N天的告警趋势
     * @param days 天数
     * @return 告警趋势数据
     */
    List<Map<String, Object>> selectAlarmTrend(@Param("days") int days,
                                               @Param("level") String level,
                                               @Param("taskId") String taskId);

    List<Map<String, Object>> selectAlarmTrendByUserId(@Param("days") int days,
                                                       @Param("userId") Integer userId,
                                                       @Param("level") String level,
                                                       @Param("taskId") String taskId);

    /**
     * 查询最近N天的告警级别分布
     * @param days 天数
     * @param level 告警级别筛选（可选）
     * @param taskId 任务ID筛选（可选）
     * @return 告警级别分布数据
     */
    List<Map<String, Object>> selectAlarmLevelDistribution(@Param("days") int days,
                                                           @Param("level") String level,
                                                           @Param("taskId") String taskId);

    List<Map<String, Object>> selectAlarmLevelDistributionByUserId(@Param("days") int days,
                                                                   @Param("userId") Integer userId,
                                                                   @Param("level") String level,
                                                                   @Param("taskId") String taskId);

    /**
     * 查询最近N天的告警根因分析统计
     */
    List<Map<String, Object>> selectAlarmRootCauseStatistics(@Param("days") int days,
                                                               @Param("level") String level,
                                                               @Param("taskId") String taskId);

    List<Map<String, Object>> selectAlarmRootCauseStatisticsByUserId(@Param("days") int days,
                                                                     @Param("userId") Integer userId,
                                                                     @Param("level") String level,
                                                                     @Param("taskId") String taskId);

    /**
     * 查询最近N天的告警统计
     */
    Map<String, Object> selectAlarmStatistics(@Param("days") int days,
                                               @Param("level") String level,
                                               @Param("taskId") String taskId);

    Map<String, Object> selectAlarmStatisticsByUserId(@Param("days") int days,
                                                      @Param("userId") Integer userId,
                                                      @Param("level") String level,
                                                      @Param("taskId") String taskId);
}
