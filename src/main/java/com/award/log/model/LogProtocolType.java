package com.award.log.model;

/**
 * 日志协议类型枚举（带特征编码，时序检测维度补充）
 * 创新点：协议特征码用于多维特征向量构建，提升异常检测精准度
 */
public enum LogProtocolType {
    LINUX_SYSTEM_LOG("Linux系统日志协议", 1),          // Linux内核/syslog/sshd日志
    WINDOWS_EVENT_LOG("Windows事件日志协议", 2),       // Windows Event ID/事件查看器日志
    APPLICATION_LOG("应用程序日志协议", 3),            // SpringBoot/Java/微服务业务日志
    DATABASE_LOG("数据库日志协议", 4),                 // MySQL/Oracle/PGSQL数据库操作日志
    NETWORK_DEVICE_LOG("网络设备日志协议", 5),         // 路由器/防火墙/交换机网络报文日志
    UNIVERSAL_TEXT_LOG("通用文本日志协议", 0),         // 无明确特征通用日志（兜底兼容）
    UNKNOWN_LOG_TYPE("未识别日志协议", -1);            // 特征匹配失败日志（异常标记）

    private final String protocolDesc; // 协议中文描述（可视化展示）
    private final int protocolCode;     // 协议特征编码（时序检测向量维度）

    LogProtocolType(String protocolDesc, int protocolCode) {
        this.protocolDesc = protocolDesc;
        this.protocolCode = protocolCode;
    }

    public String getProtocolDesc() { return protocolDesc; }
    public int getProtocolCode() { return protocolCode; }
}
