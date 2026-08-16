package com.award.log.mapper;

import com.award.log.model.LogAnalysisDetail;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;
import java.util.Map;

@Mapper
public interface LogAnalysisDetailMapper {
    List<LogAnalysisDetail> selectByTaskId(String taskId);

    long countByTaskId(@Param("taskId") String taskId);

    long countAnomaliesByTaskId(@Param("taskId") String taskId);

    List<LogAnalysisDetail> selectAnomaliesByTaskId(@Param("taskId") String taskId, @Param("limit") int limit);

    List<LogAnalysisDetail> selectByTaskIdPage(@Param("taskId") String taskId,
                                               @Param("offset") int offset,
                                               @Param("limit") int limit,
                                               @Param("anomalyOnly") Boolean anomalyOnly);

    List<Map<String, Object>> selectSeverityDistributionByTaskId(@Param("taskId") String taskId);

    int insert(LogAnalysisDetail detail);
    int batchInsert(List<LogAnalysisDetail> details);
    
    // 查询总日志数
    long countAll();

    /** 近 N 日内（按关联任务创建时间）的明细条数 */
    long countSinceDays(@Param("days") int days);
    

    
    /** 根据任务ID删除任务详情 */
    int deleteByTaskId(String taskId);

    /**
     * 按日志等级统计近期任务窗口内的异常明细条数（关联任务创建时间）
     */
    List<Map<String, Object>> selectAnomalySeverityDistribution(@Param("days") int days);

    /** 近 N 日任务窗口内的明细（ES 不可用时的 MariaDB 回退查询） */
    List<LogAnalysisDetail> selectRecentDetails(
            @Param("days") int days,
            @Param("severity") String severity,
            @Param("keyword") String keyword,
            @Param("anomalyOnly") Boolean anomalyOnly,
            @Param("limit") int limit);
}
