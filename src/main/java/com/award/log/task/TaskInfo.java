package com.award.log.task;

import com.award.log.dto.EnhancedLogParseResultEntity;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Data
public class TaskInfo {
    private String taskId;
    private Integer userId;
    private String fileName;
    private String status;
    private int progress;
    private String currentStep;
    private String errorMsg;
    /** 默认仅为异常抽样，非全量；全量请走 /report/{id}/details */
    private List<EnhancedLogParseResultEntity> result;
    private String aiDiagnosis;
    private LocalDateTime createTime;
    private TaskSummary summary;

    @Data
    public static class TaskSummary {
        private int totalLogs;
        private int anomalyCount;
        private double anomalyRate;
        private long costTime;
        /** true 表示 result 未含全部明细（仅异常抽样） */
        private boolean resultTruncated;
        /** 本次 result 条目数 */
        private int resultReturned;
        /** DB 中该任务明细总数 */
        private long detailTotal;
        /** 分析时是否触达行数上限 */
        private boolean lineCapApplied;
        private int linesSkipped;
        /** 全量等级分布（供图表，无需下发明细） */
        private Map<String, Integer> severityCounts = new LinkedHashMap<>();
    }
}
