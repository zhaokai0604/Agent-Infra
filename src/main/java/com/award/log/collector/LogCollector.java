package com.award.log.collector;

import java.util.List;

/**
 * 日志采集器接口
 * 定义日志采集的核心方法
 */
public interface LogCollector {

    /**
     * 开始采集日志
     */
    void start();

    /**
     * 停止采集日志
     */
    void stop();

    /**
     * 采集日志数据
     * @return 采集到的日志列表
     */
    List<String> collect();

    /**
     * 获取采集器名称
     * @return 采集器名称
     */
    String getName();

    /**
     * 检查采集器状态
     * @return 是否运行中
     */
    boolean isRunning();
}
