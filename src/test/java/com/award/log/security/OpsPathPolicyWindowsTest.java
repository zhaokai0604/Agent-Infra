package com.award.log.security;

import com.award.log.config.AgentOpsProperties;
import com.award.log.util.OsRuntime;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class OpsPathPolicyWindowsTest {

    @Test
    void windowsDefaults_keepTempWritableButDoNotExposeDriveRoots() {
        assumeTrue(OsRuntime.isWindows());

        AgentOpsProperties properties = new AgentOpsProperties();
        properties.setPaths(AgentOpsProperties.Paths.linuxKylinDefaults());

        OpsPathPolicy policy = new OpsPathPolicy(properties);
        ReflectionTestUtils.setField(policy, "logCollectorFilePath", "");
        policy.applyFrom(properties.getPaths());

        assertTrue(policy.isAllowedCleanDirectory(System.getProperty("java.io.tmpdir")),
                "当前进程临时目录应保持可清理，避免默认删除链路失效");
        assertFalse(policy.isAllowedLogReadPath("D:/private/app-secrets.txt"),
                "默认日志读取白名单不应扩张到整个数据盘根目录");
        assertFalse(policy.isAllowedLogCleanupPath("C:/ProgramData/app/runtime.log"),
                "默认日志删除白名单不应覆盖整个 ProgramData");
        assertFalse(policy.isAllowedDiskInsightRoot("E:/"),
                "磁盘热点扫描不应默认放开整盘根目录");
    }
}
