package com.award.log.service;

import com.award.log.dto.EnhancedLogParseResultEntity;

import java.io.File;
import java.io.IOException;
import java.util.List;

public interface LogAnalysisService {
    /**
     * 异步启动分析任务
     * @param logFile 本地临时文件
     * @param taskId 任务ID
     */
    void startAnalysisAsync(File logFile, String taskId);
    
    /**
     * 暂停分析任务
     * @param taskId 任务ID
     */
    void pauseAnalysis(String taskId);
    
    /**
     * 恢复分析任务
     * @param taskId 任务ID
     */
    void resumeAnalysis(String taskId);
    
    /**
     * 取消分析任务
     * @param taskId 任务ID
     */
    void cancelAnalysis(String taskId);

    /**
     * 将分析结果写入 {@code target/output/{taskId}/} 下的 HTML/CSV（磁盘文件缺失时供下载接口补救）。
     */
    void ensureReportArtifacts(String taskId, List<EnhancedLogParseResultEntity> results) throws IOException;
}