package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.net.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络日志采集器
 * 支持通过TCP/UDP协议接收网络日志，如Syslog
 */
@Slf4j
public class NetworkLogCollector implements LogCollector {

    private final int port;
    private final String protocol;
    private final String name;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final List<String> collectedLogs;
    private ServerSocket tcpServerSocket;
    private DatagramSocket udpServerSocket;

    public NetworkLogCollector(int port, String protocol, String name) {
        this.port = port;
        this.protocol = protocol.toUpperCase();
        this.name = name;
        this.executorService = Executors.newCachedThreadPool();
        this.collectedLogs = new ArrayList<>();
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[网络日志采集器] 开始采集，{}端口: {}", protocol, port);
            if ("TCP".equals(protocol)) {
                executorService.submit(this::startTcpServer);
            } else if ("UDP".equals(protocol)) {
                executorService.submit(this::startUdpServer);
            } else {
                log.error("[网络日志采集器] 不支持的协议: {}", protocol);
                running.set(false);
            }
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[网络日志采集器] 停止采集，{}端口: {}", protocol, port);
            if (tcpServerSocket != null && !tcpServerSocket.isClosed()) {
                try {
                    tcpServerSocket.close();
                } catch (IOException e) {
                    log.error("[网络日志采集器] 关闭TCP服务器异常", e);
                }
            }
            if (udpServerSocket != null && !udpServerSocket.isClosed()) {
                udpServerSocket.close();
            }
            executorService.shutdown();
        }
    }

    @Override
    public List<String> collect() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            collectedLogs.clear();
            return logs;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void startTcpServer() {
        try {
            tcpServerSocket = new ServerSocket(port);
            log.info("[网络日志采集器] TCP服务器已启动，监听端口: {}", port);
            
            while (running.get()) {
                try {
                    Socket clientSocket = tcpServerSocket.accept();
                    executorService.submit(() -> handleTcpClient(clientSocket));
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("[网络日志采集器] TCP服务器异常", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[网络日志采集器] 启动TCP服务器失败", e);
            running.set(false);
        }
    }

    private void handleTcpClient(Socket clientSocket) {
        try (clientSocket;
             var inputStream = clientSocket.getInputStream();
             var reader = new java.io.BufferedReader(new java.io.InputStreamReader(inputStream))) {
            
            String line;
            while ((line = reader.readLine()) != null && running.get()) {
                synchronized (collectedLogs) {
                    collectedLogs.add(line);
                }
            }
        } catch (IOException e) {
            if (running.get()) {
                log.error("[网络日志采集器] 处理TCP客户端异常", e);
            }
        }
    }

    private void startUdpServer() {
        try {
            udpServerSocket = new DatagramSocket(port);
            log.info("[网络日志采集器] UDP服务器已启动，监听端口: {}", port);
            
            byte[] buffer = new byte[65536];
            while (running.get()) {
                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    udpServerSocket.receive(packet);
                    String logMessage = new String(packet.getData(), 0, packet.getLength());
                    synchronized (collectedLogs) {
                        collectedLogs.add(logMessage);
                    }
                } catch (IOException e) {
                    if (running.get()) {
                        log.error("[网络日志采集器] UDP服务器异常", e);
                    }
                }
            }
        } catch (IOException e) {
            log.error("[网络日志采集器] 启动UDP服务器失败", e);
            running.set(false);
        }
    }
}
