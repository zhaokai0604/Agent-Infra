package com.award.log.collector.impl;

import com.award.log.security.signal.SecuritySignal;
import com.award.log.security.signal.SecuritySignalService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class NetworkLogCollectorV2Test {

    @Test
    void udpRuntimePathIngestsSuricataAndNetflowSignals() throws Exception {
        SecuritySignalService service = Mockito.mock(SecuritySignalService.class);
        int port = nextFreePort();
        NetworkLogCollectorV2 collector = new NetworkLogCollectorV2(port, "UDP", service);

        when(service.ingest("suricata", "Suricata alert"))
                .thenReturn(new SecuritySignal(
                        "id", "NIDS", "alert", "suricata title", "HIGH", 80, 0.9,
                        "sensor", "host", "1.1.1.1", "2.2.2.2",
                        "http", null, null, 1L, 2L, true, List.of(), "detail", "{}"));
        when(service.ingest("netflow", "NetFlow summary")).thenReturn(new SecuritySignal(
                "id2", "NIDS", "flow", "flow alert", "MEDIUM", 40, 0.7,
                "sensor", "host", "1.1.1.1", "2.2.2.2",
                "http", null, null, 1L, 2L, false, List.of(), "detail", "{}"));

        collector.start();
        waitUntilReady(port);

        sendUdp(port, "Suricata alert");
        sendUdp(port, "NetFlow summary");
        sendUdp(port, "plain message");

        List<String> logs = waitForLogs(collector, 3);
        collector.stop();

        verify(service).ingest("suricata", "Suricata alert");
        verify(service).ingest("netflow", "NetFlow summary");
        assertTrue(logs.contains("[SURICATA] suricata title"));
        assertTrue(logs.contains("[NETFLOW] NetFlow summary"));
        assertTrue(logs.contains("[NETWORK] plain message"));
    }

    @Test
    void startOnBusyPortFallsBackToStoppedState() throws Exception {
        SecuritySignalService service = Mockito.mock(SecuritySignalService.class);
        try (DatagramSocket occupied = new DatagramSocket(0)) {
            int port = occupied.getLocalPort();
            NetworkLogCollectorV2 collector = new NetworkLogCollectorV2(port, "UDP", service);

            collector.start();
            waitUntilStopped(collector);

            assertFalse(collector.isRunning());
            assertEquals(List.of(), collector.collect());
        }
    }

    private static void sendUdp(int port, String message) throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length);
            packet.setSocketAddress(new java.net.InetSocketAddress("127.0.0.1", port));
            socket.send(packet);
        }
    }

    private static List<String> waitForLogs(NetworkLogCollectorV2 collector, int expectedSize) throws Exception {
        long deadline = System.currentTimeMillis() + 5_000L;
        List<String> allLogs = new ArrayList<>();
        while (System.currentTimeMillis() < deadline) {
            List<String> logs = collector.collect();
            if (!logs.isEmpty()) {
                allLogs.addAll(logs);
            }
            if (allLogs.size() >= expectedSize) {
                return allLogs;
            }
            Thread.sleep(50L);
        }
        throw new SocketTimeoutException("Timed out waiting for collector logs");
    }

    private static void waitUntilReady(int port) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            try (DatagramSocket probe = new DatagramSocket()) {
                probe.connect(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (Exception ignored) {
                Thread.sleep(20L);
            }
        }
        throw new SocketTimeoutException("Collector socket was not ready");
    }

    private static void waitUntilStopped(NetworkLogCollectorV2 collector) throws Exception {
        long deadline = System.currentTimeMillis() + 2_000L;
        while (System.currentTimeMillis() < deadline) {
            if (!collector.isRunning()) {
                return;
            }
            Thread.sleep(20L);
        }
        throw new SocketTimeoutException("Collector did not stop after bind failure");
    }

    private static int nextFreePort() throws Exception {
        try (DatagramSocket socket = new DatagramSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
