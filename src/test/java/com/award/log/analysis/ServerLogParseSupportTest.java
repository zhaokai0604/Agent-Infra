package com.award.log.analysis;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.junit.jupiter.api.Assertions.*;

class ServerLogParseSupportTest {

    @Test
    void nginxErrorLog_time() {
        String line = "2024/08/01 12:00:01 [error] 12345#0: *1 connect() failed (111: Connection refused)";
        LocalDateTime t = ServerLogParseSupport.tryNginxErrorStyle(line);
        assertNotNull(t);
        assertEquals(2024, t.getYear());
        assertEquals(8, t.getMonthValue());
    }

    @Test
    void tomcat_time() {
        String line = "10-Feb-2024 14:22:01.123 INFO [main] org.apache.catalina.startup start";
        assertNotNull(ServerLogParseSupport.tryTomcatStyle(line));
    }

    @Test
    void postgres_style_time() {
        String line = "2024-02-10 14:22:01.123 UTC LOG: checkpoint starting";
        assertNotNull(ServerLogParseSupport.tryRdbmsLine(line));
    }
}
