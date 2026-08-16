package com.award.log.config;

import com.award.log.collector.LogCollectorManager;
import com.award.log.collector.impl.NetworkLogCollector;
import com.award.log.collector.impl.NetworkLogCollectorV2;
import com.award.log.collector.impl.KafkaLogProducer;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import javax.sql.DataSource;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.Mockito.mock;

class LogCollectorConfigTest {

    @Test
    void udpCollectorUsesNetworkLogCollectorV2() {
        LogCollectorConfig config = new LogCollectorConfig(
                mock(KafkaLogProducer.class),
                mock(DataSource.class),
                mock(SecuritySignalService.class));
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "filePath", "missing-path-for-test");
        ReflectionTestUtils.setField(config, "networkEnabled", true);
        ReflectionTestUtils.setField(config, "networkProtocol", "UDP");
        ReflectionTestUtils.setField(config, "networkPort", 0);
        ReflectionTestUtils.setField(config, "dbEnabled", false);

        LogCollectorManager manager = config.logCollectorManager();

        assertNotNull(manager.getCollector("NetworkLogCollectorV2"));
        assertInstanceOf(NetworkLogCollectorV2.class, manager.getCollector("NetworkLogCollectorV2"));
        manager.stopAll();
    }

    @Test
    void tcpCollectorKeepsLegacyNetworkCollector() {
        LogCollectorConfig config = new LogCollectorConfig(
                null,
                mock(DataSource.class),
                mock(SecuritySignalService.class));
        ReflectionTestUtils.setField(config, "enabled", true);
        ReflectionTestUtils.setField(config, "filePath", "missing-path-for-test");
        ReflectionTestUtils.setField(config, "networkEnabled", true);
        ReflectionTestUtils.setField(config, "networkProtocol", "TCP");
        ReflectionTestUtils.setField(config, "networkPort", 0);
        ReflectionTestUtils.setField(config, "dbEnabled", false);

        LogCollectorManager manager = config.logCollectorManager();

        assertNotNull(manager.getCollector("network-collector"));
        assertInstanceOf(NetworkLogCollector.class, manager.getCollector("network-collector"));
        manager.stopAll();
    }
}
