package com.award.log.service;

import java.util.Map;

public interface AlarmLifecycleService {
    boolean acknowledge(String alarmId, String operator);

    boolean handle(String alarmId, String operator);

    boolean close(String alarmId, String operator);

    Map<String, Object> silenceWindow(String startTime, String endTime);

    /** 当前是否处于静默窗口（静默期内抑制新告警与自动升级）。 */
    boolean isSilencedNow();
}
