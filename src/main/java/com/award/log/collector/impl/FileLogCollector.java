package com.award.log.collector.impl;

import com.award.log.collector.LogCollector;
import lombok.extern.slf4j.Slf4j;

import java.io.*;
import java.nio.file.*;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.TimeUnit;

/**
 * 文件日志采集器
 * 支持从本地文件或目录中采集日志
 */
@Slf4j
public class FileLogCollector implements LogCollector {

    private static final int DEFAULT_BUFFER_MAX_LINES = 8000;

    private final String filePath;
    private final String name;
    private final Set<String> includeExtensions;
    private final Set<String> excludeDirectories;
    private final int maxDepth;
    private final int pollIntervalMs;
    private final int bufferMaxLines;
    private final boolean startAtEof;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final ExecutorService executorService;
    private final List<String> collectedLogs;
    private final Map<String, Long> fileReadPositions = new ConcurrentHashMap<>();
    private final Map<WatchKey, Path> watchKeyPathMap = new ConcurrentHashMap<>();
    private long droppedLines;

    public FileLogCollector(String filePath, String name) {
        this(filePath, name,
                ".log,.txt,.out,.err,.debug,.info,.warn,.error,.fatal,.access,.audit,.trace",
                "node_modules,target,.git,.idea,.vscode,.cursor");
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories) {
        this(filePath, name, includeExtensions, excludeDirectories, 4, 5000,
                DEFAULT_BUFFER_MAX_LINES, true);
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories,
                            int maxDepth, int pollIntervalMs) {
        this(filePath, name, includeExtensions, excludeDirectories, maxDepth, pollIntervalMs,
                DEFAULT_BUFFER_MAX_LINES, true);
    }

    public FileLogCollector(String filePath, String name, String includeExtensions, String excludeDirectories,
                            int maxDepth, int pollIntervalMs, int bufferMaxLines, boolean startAtEof) {
        this.filePath = filePath;
        this.name = name;
        this.maxDepth = Math.max(1, maxDepth);
        this.pollIntervalMs = Math.max(1000, pollIntervalMs);
        this.bufferMaxLines = Math.max(100, bufferMaxLines);
        this.startAtEof = startAtEof;
        this.executorService = Executors.newSingleThreadExecutor();
        this.collectedLogs = new ArrayList<>();
        this.includeExtensions = parseCsvToLowercaseSet(includeExtensions);
        this.excludeDirectories = parseCsvToLowercaseSet(excludeDirectories);
    }

    @Override
    public void start() {
        if (running.compareAndSet(false, true)) {
            log.info("[文件日志采集器] 开始采集，文件路径: {}", filePath);
            executorService.submit(this::collectLogsContinuously);
        }
    }

    @Override
    public void stop() {
        if (running.compareAndSet(true, false)) {
            log.info("[文件日志采集器] 停止采集，文件路径: {}", filePath);
            executorService.shutdown();
        }
    }

    @Override
    public List<String> collect() {
        synchronized (collectedLogs) {
            List<String> logs = new ArrayList<>(collectedLogs);
            collectedLogs.clear();
            return logs;
        }
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public boolean isRunning() {
        return running.get();
    }

    private void collectLogsContinuously() {
        Path root = Paths.get(filePath);
        if (Files.notExists(root)) {
            log.warn("[文件日志采集器] 文件或目录不存在: {}", filePath);
            return;
        }
        if (Files.isDirectory(root)) {
            watchDirectoryRecursively(root);
        } else {
            pollSingleFile(root);
        }
    }

    private void watchDirectoryRecursively(Path scanRoot) {
        this.root = scanRoot;
        try (WatchService watchService = FileSystems.getDefault().newWatchService()) {
            if (startAtEof) {
                seedEofPositions(scanRoot, 0);
            } else {
                collectFromDirectory(scanRoot, 0);
            }
            registerAllDirectories(scanRoot, watchService, 0);
            while (running.get()) {
                WatchKey key = watchService.poll(pollIntervalMs, TimeUnit.MILLISECONDS);
                if (key == null) {
                    continue;
                }
                processWatchKey(key, watchService);
            }
        } catch (Exception e) {
            log.error("[文件日志采集器] WatchService异常，回退轮询: {}", e.getMessage());
            fallbackDirectoryPolling(scanRoot);
        }
    }

    private void fallbackDirectoryPolling(Path scanRoot) {
        this.root = scanRoot;
        if (startAtEof && fileReadPositions.isEmpty()) {
            try {
                seedEofPositions(scanRoot, 0);
            } catch (Exception e) {
                log.debug("[文件日志采集器] EOF 种子失败: {}", e.getMessage());
            }
        }
        while (running.get()) {
            try {
                collectFromDirectory(scanRoot, 0);
                Thread.sleep(pollIntervalMs);
            } catch (Exception ex) {
                log.warn("[文件日志采集器] 回退轮询异常: {}", ex.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    private void pollSingleFile(Path file) {
        if (startAtEof && !fileReadPositions.containsKey(file.toAbsolutePath().toString())) {
            try {
                long len = Files.size(file);
                fileReadPositions.put(file.toAbsolutePath().toString(), len);
            } catch (Exception e) {
                log.debug("[文件日志采集器] 单文件 EOF 种子失败: {}", e.getMessage());
            }
        }
        while (running.get()) {
            try {
                collectFromFile(file);
                Thread.sleep(pollIntervalMs);
            } catch (Exception e) {
                log.warn("[文件日志采集器] 文件轮询异常: {}", e.getMessage());
                sleepQuietly(3000);
            }
        }
    }

    private void processWatchKey(WatchKey key, WatchService watchService) throws IOException {
        Path dir = watchKeyPathMap.get(key);
        if (dir == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            WatchEvent.Kind<?> kind = event.kind();
            if (kind == StandardWatchEventKinds.OVERFLOW) {
                continue;
            }
            Path changedRelativePath = (Path) event.context();
            Path changedPath = dir.resolve(changedRelativePath);
            if (Files.isDirectory(changedPath) && kind == StandardWatchEventKinds.ENTRY_CREATE) {
                registerAllDirectories(changedPath, watchService, depthOf(dir));
                continue;
            }
            if (Files.isRegularFile(changedPath) && isLogFile(changedPath)) {
                collectFromFile(changedPath);
            }
        }
        boolean valid = key.reset();
        if (!valid) {
            watchKeyPathMap.remove(key);
        }
    }

    private int depthOf(Path directory) {
        try {
            return rootDepth(root, directory);
        } catch (Exception e) {
            return maxDepth;
        }
    }

    private int rootDepth(Path root, Path dir) {
        if (dir == null || dir.equals(root)) {
            return 0;
        }
        Path parent = dir.getParent();
        if (parent == null || parent.equals(dir)) {
            return 1;
        }
        return 1 + rootDepth(root, parent);
    }

    private Path root;

    private void registerAllDirectories(Path scanRoot, WatchService watchService, int depth) {
        if (depth >= maxDepth) {
            return;
        }
        try {
            Files.walk(scanRoot, Math.max(1, maxDepth - depth))
                    .filter(Files::isDirectory)
                    .filter(this::isAllowedDirectory)
                    .forEach(path -> registerDirectory(path, watchService));
        } catch (Exception e) {
            log.debug("[文件日志采集器] 注册监视目录失败: {}", scanRoot, e);
        }
    }

    private void registerDirectory(Path directory, WatchService watchService) {
        try {
            WatchKey key = directory.register(watchService,
                    StandardWatchEventKinds.ENTRY_CREATE,
                    StandardWatchEventKinds.ENTRY_MODIFY,
                    StandardWatchEventKinds.ENTRY_DELETE);
            watchKeyPathMap.put(key, directory);
        } catch (IOException e) {
            log.debug("[文件日志采集器] 目录注册失败: {}", directory, e);
        }
    }

    private void collectFromFile(Path path) throws IOException {
        String fileName = path.toAbsolutePath().toString();
        long lastReadPosition = fileReadPositions.getOrDefault(fileName, 0L);
        int linesThisTick = 0;
        final int maxPerTick = 2000;

        try (RandomAccessFile file = new RandomAccessFile(path.toFile(), "r")) {
            if (lastReadPosition > file.length()) {
                lastReadPosition = 0L;
            }
            file.seek(lastReadPosition);
            while (true) {
                long mark = file.getFilePointer();
                String line = file.readLine();
                if (line == null) {
                    fileReadPositions.put(fileName, file.getFilePointer());
                    break;
                }
                if (!appendBufferedLine(line)) {
                    // 缓冲满：位点停在本行之前，下一轮再读，避免永久丢行
                    fileReadPositions.put(fileName, mark);
                    break;
                }
                linesThisTick++;
                fileReadPositions.put(fileName, file.getFilePointer());
                if (linesThisTick >= maxPerTick) {
                    break;
                }
            }
        }
    }

    private boolean appendBufferedLine(String line) {
        synchronized (collectedLogs) {
            if (collectedLogs.size() >= bufferMaxLines) {
                droppedLines++;
                if (droppedLines == 1 || droppedLines % 1000 == 0) {
                    log.warn("[文件日志采集器] 缓冲已满 ({}), 已丢弃 {} 行，请检查调度排水",
                            bufferMaxLines, droppedLines);
                }
                return false;
            }
            collectedLogs.add(line);
            return true;
        }
    }

    /** 启动种子：把已有日志文件位点推到 EOF，只跟增量。 */
    private void seedEofPositions(Path directory, int depth) throws IOException {
        if (!isAllowedDirectory(directory) || depth >= maxDepth) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        seedEofPositions(entry, depth + 1);
                    } else if (Files.isRegularFile(entry) && isLogFile(entry)) {
                        fileReadPositions.put(entry.toAbsolutePath().toString(), Files.size(entry));
                    }
                } catch (Exception e) {
                    log.debug("[文件日志采集器] EOF 种子跳过: {}", entry, e);
                }
            }
        }
    }

    private void collectFromDirectory(Path directory, int depth) throws IOException {
        if (!isAllowedDirectory(directory) || depth >= maxDepth) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path entry : stream) {
                try {
                    if (Files.isDirectory(entry)) {
                        collectFromDirectory(entry, depth + 1);
                    } else if (Files.isRegularFile(entry) && isLogFile(entry)) {
                        collectFromFile(entry);
                    }
                } catch (Exception e) {
                    log.debug("[文件日志采集器] 无法访问: {}", entry, e);
                }
            }
        } catch (Exception e) {
            log.debug("[文件日志采集器] 无法访问目录: {}", directory, e);
        }
    }
    
    /**
     * 检查是否为日志文件
     * 支持多种常见的日志文件后缀
     */
    private boolean isLogFile(Path path) {
        String fileName = path.getFileName().toString().toLowerCase();
        for (String ext : includeExtensions) {
            if (!ext.isEmpty() && fileName.endsWith(ext)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedDirectory(Path path) {
        String name = path.getFileName() == null ? "" : path.getFileName().toString().toLowerCase();
        if (excludeDirectories.contains(name)) {
            return false;
        }
        if (File.separatorChar == '\\') {
            String normalized = path.toAbsolutePath().toString().replace('\\', '/').toLowerCase(Locale.ROOT);
            if (normalized.startsWith("c:/programdata/microsoft/")
                    || normalized.startsWith("c:/programdata/windows defender")
                    || normalized.startsWith("c:/$recycle.bin")
                    || normalized.startsWith("c:/system volume information")) {
                return false;
            }
        }
        return true;
    }

    private Set<String> parseCsvToLowercaseSet(String csv) {
        if (csv == null || csv.isBlank()) {
            return new HashSet<>();
        }
        Set<String> result = new HashSet<>();
        Arrays.stream(csv.split(","))
                .map(String::trim)
                .map(String::toLowerCase)
                .forEach(result::add);
        return result;
    }

    private void sleepQuietly(long ms) {
        try {
            Thread.sleep(ms);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }
}
