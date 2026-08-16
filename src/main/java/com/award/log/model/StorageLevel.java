package com.award.log.model;

import lombok.Getter;

/**
 * 存储级别枚举
 * 定义不同的存储级别：热数据、温数据、冷数据
 */
@Getter
public enum StorageLevel {

    /**
     * 热数据
     * 最近产生的、频繁访问的日志
     * 存储在Elasticsearch中，提供快速查询
     */
    HOT("hot", "热数据", 7), // 保留7天

    /**
     * 温数据
     * 较早期的、偶尔访问的日志
     * 存储在压缩文件中，提供基本查询
     */
    WARM("warm", "温数据", 30), // 保留30天

    /**
     * 冷数据
     * 早期的、很少访问的日志
     * 存储在归档文件中，提供有限查询
     */
    COLD("cold", "冷数据", 90); // 保留90天

    private final String code;
    private final String name;
    private final int retentionDays;

    StorageLevel(String code, String name, int retentionDays) {
        this.code = code;
        this.name = name;
        this.retentionDays = retentionDays;
    }

    /**
     * 根据代码获取存储级别
     * @param code 存储级别代码
     * @return 存储级别枚举
     */
    public static StorageLevel getByCode(String code) {
        for (StorageLevel level : values()) {
            if (level.getCode().equals(code)) {
                return level;
            }
        }
        return HOT;
    }
}
