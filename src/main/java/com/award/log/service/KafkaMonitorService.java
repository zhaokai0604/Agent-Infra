package com.award.log.service;

import java.util.Map;

public interface KafkaMonitorService {
    Map<String, Object> snapshot();
}
