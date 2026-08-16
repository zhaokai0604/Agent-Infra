package com.award.log.analysis;

import com.award.log.model.LogProtocolType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 比赛/回归：Linux + 容器日志格式解析烟测
 */
class LinuxLogParseSupportTest {

    @Test
    void criKubernetes_extractsTimeAndProtocol() {
        String line = "2020-01-01T12:00:00.123456789Z stdout F hello from container";
        assertTrue(LinuxLogParseSupport.isCriKubernetesLine(line));
        LocalDateTime t = LinuxLogParseSupport.tryCriKubernetesTimestamp(line);
        assertNotNull(t);
        assertEquals(LogProtocolType.LINUX_SYSTEM_LOG, LogLineParseSupport.detectProtocol(line));
    }

    @Test
    void dockerJsonFile_extractsTime() {
        String line = "{\"log\":\"error\\n\",\"stream\":\"stderr\",\"time\":\"2021-06-15T10:00:00.5Z\"}";
        assertTrue(LinuxLogParseSupport.isDockerJsonFileLine(line));
        LocalDateTime t = LinuxLogParseSupport.tryContainerDockerJsonTimestamp(line);
        assertNotNull(t);
        assertEquals(LogProtocolType.LINUX_SYSTEM_LOG, LogLineParseSupport.detectProtocol(line));
    }

    @Test
    void journalJson_microseconds() {
        String line = "{\"__REALTIME_TIMESTAMP\":\"1609459200000000\",\"MESSAGE\":\"boot\"}";
        LocalDateTime t = LinuxLogParseSupport.tryJournalJsonRealtime(line);
        assertNotNull(t);
    }

    @Test
    void stripPriAndSyslogTraditional() {
        String line = "<38>Feb 10 14:22:01 host sshd[1234]: Accepted publickey";
        String stripped = LinuxLogParseSupport.stripLeadingSyslogPri(line);
        assertTrue(stripped.contains("Feb 10"));
        assertEquals("1234", LinuxLogParseSupport.extractTagPid(line));
    }
}
