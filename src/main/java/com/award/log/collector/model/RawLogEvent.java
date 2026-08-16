package com.award.log.collector.model;

import lombok.Data;

/**
 * 采集层原始日志事件模型
 */
@Data
public class RawLogEvent {

    private String eventId;
    private String sourceId;
    private String sourceType;
    private String host;
    private Long offset;
    private Long ingestTime;
    private Long eventTime;
    private String level;
    private String content;
}
