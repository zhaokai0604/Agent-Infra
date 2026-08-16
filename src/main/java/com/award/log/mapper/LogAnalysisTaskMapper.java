package com.award.log.mapper;

import com.award.log.model.LogAnalysisTask;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface LogAnalysisTaskMapper {
    LogAnalysisTask selectById(String taskId);

    LogAnalysisTask selectByIdAndUserId(@Param("taskId") String taskId, @Param("userId") Integer userId);

    List<LogAnalysisTask> selectAll();

    List<LogAnalysisTask> selectAllByUserId(@Param("userId") Integer userId);

    List<LogAnalysisTask> selectPage(@Param("offset") int offset, @Param("limit") int limit);

    List<LogAnalysisTask> selectPageWithFilter(@Param("offset") int offset,
                                               @Param("limit") int limit,
                                               @Param("fileName") String fileName,
                                               @Param("status") String status,
                                               @Param("createTimeStart") LocalDateTime createTimeStart,
                                               @Param("createTimeEnd") LocalDateTime createTimeEnd);

    List<LogAnalysisTask> selectPageWithFilterByUserId(@Param("offset") int offset,
                                                       @Param("limit") int limit,
                                                       @Param("userId") Integer userId,
                                                       @Param("fileName") String fileName,
                                                       @Param("status") String status,
                                                       @Param("createTimeStart") LocalDateTime createTimeStart,
                                                       @Param("createTimeEnd") LocalDateTime createTimeEnd);

    long countAll();

    long countAllByUserId(@Param("userId") Integer userId);

    long countSinceDays(@Param("days") int days);

    long countWithFilter(@Param("fileName") String fileName,
                         @Param("status") String status,
                         @Param("createTimeStart") LocalDateTime createTimeStart,
                         @Param("createTimeEnd") LocalDateTime createTimeEnd);

    long countWithFilterByUserId(@Param("userId") Integer userId,
                                 @Param("fileName") String fileName,
                                 @Param("status") String status,
                                 @Param("createTimeStart") LocalDateTime createTimeStart,
                                 @Param("createTimeEnd") LocalDateTime createTimeEnd);

    int insert(LogAnalysisTask record);

    int updateById(LogAnalysisTask task);

    List<Map<String, Object>> getTaskTrend(@Param("days") int days);

    List<Map<String, Object>> getTaskStatusCount();

    List<Map<String, Object>> getTaskCompletionStatistics();

    Double getAverageProcessingTime();

    int deleteById(String taskId);
}
