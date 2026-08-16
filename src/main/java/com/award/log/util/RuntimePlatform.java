package com.award.log.util;

public interface RuntimePlatform {

    boolean isWindows();

    default String defaultLogRoot() {
        return isWindows() ? "C:\\Windows\\Logs" : "/var/log";
    }

    default String defaultTempRoot() {
        return isWindows() ? "C:\\Windows\\Temp" : "/tmp";
    }
}
