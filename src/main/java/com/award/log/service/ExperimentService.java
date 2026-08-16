package com.award.log.service;

import java.util.Map;

public interface ExperimentService {
    boolean start(String name);
    boolean stop();
    Map<String, Object> report();
    boolean isRunning();
}
