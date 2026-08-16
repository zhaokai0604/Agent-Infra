package com.award.log.mcp.tools;

import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

class ProcessToolTest {

    @Test
    @SuppressWarnings("unchecked")
    void parseOutput_skipsSelfInspectionPsCommand() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), mock(MinPrivilegeExecutor.class));
            Method method = ProcessTool.class.getDeclaredMethod("parseOutput", String.class, double.class, double.class);
            method.setAccessible(true);

            String sample = String.join("\n",
                    "root 5929 142.0 0.0 00:00 R ps -eo user,pid,pcpu,pmem,etime,stat,command --sort=-pcpu,-pmem --no-headers",
                    "root 5675 72.4 8.4 07:02 S /usr/bin/java -Dapp.management.enabled=false -jar /opt/threshcore/award-log.jar");

            List<Map<String, String>> result = (List<Map<String, String>>) method.invoke(tool, sample, 5.0, 5.0);

            assertEquals(1, result.size());
            assertTrue(result.get(0).get("command").contains("/usr/bin/java"));
            assertFalse(result.get(0).get("command").contains("ps -eo"));
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void parseOutputKeepsParentPidForAttribution() throws Exception {
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try {
            ProcessTool tool = new ProcessTool(executor, new ObjectMapper(), mock(MinPrivilegeExecutor.class));
            Method method = ProcessTool.class.getDeclaredMethod("parseOutput", String.class, double.class, double.class);
            method.setAccessible(true);
            List<Map<String, String>> result = (List<Map<String, String>>) method.invoke(tool,
                    "root 5675 1 72.4 8.4 07:02 S s_daemon --worker", 5.0, 5.0);
            assertEquals("1", result.get(0).get("ppid"));
        } finally {
            executor.shutdownNow();
        }
    }
}
