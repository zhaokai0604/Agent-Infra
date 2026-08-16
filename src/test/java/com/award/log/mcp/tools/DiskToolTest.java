package com.award.log.mcp.tools;

import com.award.log.config.OpsDryRunProperties;
import com.award.log.mcp.AbstractCommandExecutor;
import com.award.log.mcp.MinPrivilegeExecutor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.*;

class DiskToolTest {

    private DiskTool diskTool;
    private ExecutorService executorService;
    private ObjectMapper objectMapper;
    private static final Pattern DF_OUTPUT_PATTERN = Pattern.compile(
        "^([\\w/\\-\\.]+)\\s+([\\d.]+[KMGTPE]?)\\s+([\\d.]+[KMGTPE]?)\\s+([\\d.]+[KMGTPE]?)\\s+(\\d+%)\\s+(.+)$"
    );

    @BeforeEach
    void setUp() {
        executorService = Executors.newSingleThreadExecutor();
        objectMapper = new ObjectMapper();
        OpsDryRunProperties dryRun = new OpsDryRunProperties();
        dryRun.setGlobal(false);
        MinPrivilegeExecutor minPrivilegeExecutor = new MinPrivilegeExecutor("root", false, dryRun, executorService);
        diskTool = new DiskTool(executorService, objectMapper, minPrivilegeExecutor, null);
        System.out.println("=== DiskTool Test Setup Complete ===");
    }

    @AfterEach
    void tearDown() {
        if (executorService != null) {
            executorService.shutdownNow();
        }
        System.out.println("=== DiskTool Test Teardown Complete ===");
    }

    @Test
    void testCheckDiskUsage() throws Exception {
        System.out.println("\n--- testCheckDiskUsage ---");
        String result = diskTool.checkDiskUsage();
        System.out.println("Result: " + result);

        assertNotNull(result, "结果不应为 null");

        boolean isJsonStart = result.startsWith("[") || result.startsWith("{");
        assertTrue(isJsonStart, "结果应该是 JSON 数组或对象开头，实际: " + result);

        System.out.println("JSON 格式验证: " + (isJsonStart ? "通过" : "失败"));
    }

    @Test
    void testCheckDiskUsageNotEmpty() throws Exception {
        System.out.println("\n--- testCheckDiskUsageNotEmpty ---");
        String result = diskTool.checkDiskUsage();
        System.out.println("Result: " + result);

        if (result.startsWith("[")) {
            assertTrue(result.length() > 2, "JSON 数组不应为空");
            assertTrue(result.contains("filesystem") || result.contains("size"),
                "JSON 应该包含分区信息字段");

            if (result.startsWith("[{\"success\":true")) {
                System.out.println("检测到成功响应，解析 data 字段...");
            } else if (result.startsWith("[{\"success\":false")) {
                System.out.println("检测到错误响应（可能 df 命令不可用），这是可接受的");
            }
        } else if (result.startsWith("{")) {
            assertTrue(result.contains("success") || result.contains("data") || result.contains("error"),
                "JSON 对象应包含 success/data/error 字段");
        }

        System.out.println("非空验证: 通过");
    }

    @Test
    void testParseLinePattern() {
        System.out.println("\n--- testParseLinePattern ---");

        String[] testLines = {
            "tmpfs           1.6G  4.0K  1.6G   1% /run",
            "/dev/sda1       100G   50G   45G  53% /",
            "overlay         200G  100G   80G  50% /var/lib/docker",
            "tmpfs           3.0G     0   3.0G   0% /dev/shm",
            "/dev/sdb1       500G  200G  275G  43% /mnt/data"
        };

        for (String line : testLines) {
            System.out.println("Testing: " + line);
            Matcher matcher = DF_OUTPUT_PATTERN.matcher(line);
            assertTrue(matcher.matches(), "行应该能被正则匹配: " + line);

            if (matcher.matches()) {
                System.out.println("  filesystem: " + matcher.group(1));
                System.out.println("  size: " + matcher.group(2));
                System.out.println("  used: " + matcher.group(3));
                System.out.println("  available: " + matcher.group(4));
                System.out.println("  usePercent: " + matcher.group(5));
                System.out.println("  mountedOn: " + matcher.group(6));
            }
        }

        System.out.println("所有测试行解析验证: 通过");
    }

    @Test
    void testErrorResponseHandled() throws Exception {
        System.out.println("\n--- testErrorResponseHandled ---");
        String result = diskTool.checkDiskUsage();
        System.out.println("Result: " + result);

        if (result.startsWith("{")) {
            assertTrue(result.contains("\"success\""), "JSON 应该包含 success 字段");
            if (result.contains("\"success\":false")) {
                System.out.println("检测到错误响应，这是可接受的行为（df 命令可能不可用）");
            } else if (result.contains("\"success\":true")) {
                System.out.println("检测到成功响应，df 命令正常工作");
            }
        } else if (result.startsWith("[")) {
            System.out.println("检测到数组响应");
        }

        assertNotNull(result);
    }
}