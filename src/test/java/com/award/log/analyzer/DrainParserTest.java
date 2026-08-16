package com.award.log.analyzer;

import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

class DrainParserTest {

    @Test
    void shouldReturnStableTemplateId() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("ERROR user 123 timeout in mysql");
        String id2 = parser.parse("ERROR user 456 timeout in mysql");
        Assertions.assertNotNull(id1);
        Assertions.assertEquals(id1, id2);
    }

    @Test
    void shouldCreateNewTemplateForDifferentPattern() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("ERROR user 123 timeout in mysql");
        String id2 = parser.parse("WARN disk 90% usage");
        Assertions.assertNotNull(id1);
        Assertions.assertNotNull(id2);
        Assertions.assertNotEquals(id1, id2);
    }

    @Test
    void shouldHandleNullAndBlank() {
        DrainParser parser = new DrainPlusParser(8, 50);
        Assertions.assertEquals("EMPTY", parser.parse(null));
        Assertions.assertEquals("EMPTY", parser.parse(""));
        Assertions.assertEquals("EMPTY", parser.parse("   "));
    }

    @Test
    void shouldNormalizeNumbers() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("ERROR user 123 timeout");
        String id2 = parser.parse("ERROR user 456 timeout");
        Assertions.assertEquals(id1, id2);
    }

    @Test
    void shouldNormalizeIpAddresses() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("Connection from 192.168.1.100");
        String id2 = parser.parse("Connection from 10.0.0.1");
        Assertions.assertEquals(id1, id2);
    }

    @Test
    void shouldNormalizeUUIDs() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("Request id abc12345-1234-1234-1234-123456789abc");
        String id2 = parser.parse("Request id def67890-9876-9876-9876-987654321fed");
        Assertions.assertEquals(id1, id2);
    }

    @Test
    void shouldTrackTemplateCount() {
        DrainParser parser = new DrainPlusParser(8, 50);
        parser.parse("ERROR test 1");
        parser.parse("ERROR test 2");
        parser.parse("ERROR test 3");
        Assertions.assertTrue(parser.getTemplateCount() > 0);
    }

    @Test
    void shouldReturnStats() {
        DrainParser parser = new DrainPlusParser(8, 50);
        parser.parse("ERROR test 1");
        parser.parse("WARN test 2");
        Map<String, Object> stats = parser.getStats();
        Assertions.assertNotNull(stats);
        Assertions.assertTrue(stats.containsKey("totalTemplates"));
        Assertions.assertTrue(stats.containsKey("maxDepth"));
        Assertions.assertTrue(stats.containsKey("simThreshold"));
    }

    @Test
    void shouldHandleConcurrentReads() throws InterruptedException {
        DrainParser parser = new DrainPlusParser(8, 50);
        parser.parse("ERROR user 123 timeout in mysql");

        int threadCount = 10;
        int iterations = 1000;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch latch = new CountDownLatch(threadCount);

        for (int t = 0; t < threadCount; t++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        parser.parse("ERROR user 123 timeout in mysql");
                    }
                } finally {
                    latch.countDown();
                }
            });
        }

        Assertions.assertTrue(latch.await(10, TimeUnit.SECONDS));
        executor.shutdown();
    }

    @Test
    void shouldHandleMixedReadWrite() throws InterruptedException {
        DrainParser parser = new DrainPlusParser(8, 50);
        parser.parse("ERROR base template");

        int writerCount = 2;
        int readerCount = 8;
        int iterations = 500;
        CountDownLatch latch = new CountDownLatch(writerCount + readerCount);
        AtomicInteger errorCount = new AtomicInteger(0);

        ExecutorService executor = Executors.newFixedThreadPool(16);

        for (int w = 0; w < writerCount; w++) {
            final int writerId = w;
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        parser.parse("ERROR writer " + writerId + " iteration " + i);
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        for (int r = 0; r < readerCount; r++) {
            executor.submit(() -> {
                try {
                    for (int i = 0; i < iterations; i++) {
                        parser.parse("ERROR user 123 timeout in mysql");
                    }
                } catch (Exception e) {
                    errorCount.incrementAndGet();
                } finally {
                    latch.countDown();
                }
            });
        }

        Assertions.assertTrue(latch.await(30, TimeUnit.SECONDS));
        executor.shutdown();
        Assertions.assertEquals(0, errorCount.get());
    }

    @Test
    void shouldParseTemplatesInParallel() {
        DrainParser parser = new DrainPlusParser(8, 50);
        List<String> logs = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            logs.add("ERROR user " + (i % 10) + " timeout");
        }

        List<DrainParser.TemplateInfo> newTemplates = parser.parallelParse(logs, 4);

        Assertions.assertNotNull(newTemplates);
        Assertions.assertTrue(parser.getTemplateCount() > 0);
    }

    @Test
    void shouldSupportCustomDepthAndThreshold() {
        DrainParser parser = new DrainPlusParser(10, 5);
        Assertions.assertNotNull(parser);

        parser.parse("ERROR test 1");
        Map<String, Object> stats = parser.getStats();
        Assertions.assertEquals(10, stats.get("maxDepth"));
        Assertions.assertEquals(5, stats.get("simThreshold"));
    }

    @Test
    void plusParserShouldWork() {
        DrainParser parser = new DrainPlusParser(8, 50);
        String id1 = parser.parse("ERROR user 123 timeout");
        String id2 = parser.parse("ERROR user 456 timeout");
        Assertions.assertEquals(id1, id2);
        Assertions.assertEquals("DrainParserPlus", parser.getVersion());
    }

    @Test
    void parallelParseShouldReturnTemplateInfo() {
        DrainParser parser = new DrainPlusParser(8, 50);
        List<String> logs = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            logs.add("ERROR test " + i);
        }

        List<DrainParser.TemplateInfo> results = parser.parallelParse(logs, 2);

        Assertions.assertFalse(results.isEmpty());
        for (DrainParser.TemplateInfo info : results) {
            Assertions.assertNotNull(info.getTemplateId());
            Assertions.assertNotNull(info.getTemplateText());
            Assertions.assertTrue(info.getCount() >= 0);
        }
    }
}
