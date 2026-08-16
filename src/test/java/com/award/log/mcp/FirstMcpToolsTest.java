package com.award.log.mcp;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class FirstMcpToolsTest {

    private final FirstMcpTools tools = new FirstMcpTools();

    @Test
    void testGetServerTime() {
        String time = tools.getServerTime();
        assertNotNull(time);
        assertTrue(time.matches("\\d{4}-\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}"),
            "时间格式应为 yyyy-MM-dd HH:mm:ss，实际: " + time);
        System.out.println("getServerTime() = " + time);
    }

    @Test
    void testEcho() {
        String result = tools.echo("Hello");
        assertEquals("Echo: Hello", result);
        System.out.println("echo('Hello') = " + result);
    }

    @Test
    void testEchoWithEmptyString() {
        String result = tools.echo("");
        assertEquals("Echo: ", result);
    }

    @Test
    void testEchoWithChinese() {
        String result = tools.echo("你好世界");
        assertEquals("Echo: 你好世界", result);
        System.out.println("echo('你好世界') = " + result);
    }
}
