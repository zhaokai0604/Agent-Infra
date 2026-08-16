package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Spring Boot Actuator应用日志采集器
 * 用于收集应用的运行状态和日志信息
 */
@Slf4j
public class ActuatorLogCollector implements LogCollector {

    private final String actuatorUrl;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private Thread collectorThread;
    private final List<String> logs = new ArrayList<>();

    public ActuatorLogCollector(String actuatorUrl) {
        this.actuatorUrl = actuatorUrl;
    }

    @Override
    public void start() {
        if (running.get()) {
            log.warn("[Actuator采集器] 已经在运行中");
            return;
        }

        running.set(true);
        collectorThread = new Thread(this::collectActuatorMetrics);
        collectorThread.start();
        log.info("[Actuator采集器] 启动成功，监控地址: {}", actuatorUrl);
    }

    @Override
    public void stop() {
        if (!running.get()) {
            log.warn("[Actuator采集器] 已经停止");
            return;
        }

        running.set(false);
        if (collectorThread != null && collectorThread.isAlive()) {
            collectorThread.interrupt();
        }
        log.info("[Actuator采集器] 停止成功");
    }

    @Override
    public List<String> collect() {
        synchronized (logs) {
            List<String> result = new ArrayList<>(logs);
            logs.clear();
            return result;
        }
    }

    @Override
    public String getName() {
        return "ActuatorLogCollector";
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void collectActuatorMetrics() {
        while (running.get()) {
            try {
                // 采集健康状态
                collectHealth();
                
                // 采集指标
                collectMetrics();
                
                // 采集环境信息
                collectEnv();
                
                // 每30秒采集一次
                Thread.sleep(30000);
            } catch (InterruptedException e) {
                if (running.get()) {
                    log.error("[Actuator采集器] 线程被中断: {}", e.getMessage());
                }
                break;
            } catch (Exception e) {
                log.error("[Actuator采集器] 异常: {}", e.getMessage());
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException ex) {
                    break;
                }
            }
        }
    }

    private void collectHealth() throws IOException {
        String url = actuatorUrl + "/health";
        String response = sendGetRequest(url);
        synchronized (logs) {
            logs.add("[ACTUATOR-HEALTH] " + response);
        }
    }

    private void collectMetrics() throws IOException {
        String url = actuatorUrl + "/metrics";
        String response = sendGetRequest(url);
        synchronized (logs) {
            logs.add("[ACTUATOR-METRICS] " + response);
        }
    }

    private void collectEnv() throws IOException {
        String url = actuatorUrl + "/env";
        String response = sendGetRequest(url);
        synchronized (logs) {
            logs.add("[ACTUATOR-ENV] " + response);
        }
    }

    private String sendGetRequest(String urlStr) throws IOException {
        URL url = new URL(urlStr);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);

        int responseCode = connection.getResponseCode();
        if (responseCode == HttpURLConnection.HTTP_OK) {
            try (var inputStream = connection.getInputStream();
                 var scanner = new java.util.Scanner(inputStream, StandardCharsets.UTF_8.name())) {
                return scanner.useDelimiter("\\A").next();
            }
        } else {
            log.error("[Actuator采集器] 请求失败，响应码: {}, URL: {}", responseCode, urlStr);
            return "{\"error\": \"Failed to fetch\", \"status\": " + responseCode + "}";
        }
    }
}
