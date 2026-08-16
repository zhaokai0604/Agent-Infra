package com.award.log.util;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 从日志文件尾部高效读取若干行：自尾向前累加字节块直至凑够行数或到达文件头。
 */
public final class LogTailReader {

    private static final int MIN_CHUNK = 64 * 1024;
    private static final int MAX_CHUNK = 2 * 1024 * 1024;

    private LogTailReader() {
    }

    public static List<String> readTailLines(Path path, int maxLines, int initialBudgetBytes) throws IOException {
        if (maxLines <= 0) {
            return List.of();
        }
        if (!Files.isRegularFile(path) || !Files.isReadable(path)) {
            return List.of();
        }
        long size = Files.size(path);
        if (size == 0) {
            return List.of();
        }
        int budget = Math.min(MAX_CHUNK, Math.max(MIN_CHUNK, initialBudgetBytes));
        List<byte[]> chunksHeadToTail = new ArrayList<>();
        long pos = size;
        while (pos > 0) {
            long start = Math.max(0, pos - budget);
            int len = (int) (pos - start);
            byte[] buf = readBytes(path, start, len);
            chunksHeadToTail.add(0, buf);
            pos = start;
            byte[] merged = concat(chunksHeadToTail);
            List<String> lines = splitAllLines(decodeUtf8Lenient(merged));
            if (lines.size() >= maxLines || start == 0) {
                if (start > 0 && !lines.isEmpty()) {
                    lines = lines.subList(1, lines.size());
                }
                int from = Math.max(0, lines.size() - maxLines);
                return new ArrayList<>(lines.subList(from, lines.size()));
            }
            budget = Math.min(MAX_CHUNK, (int) (budget * 1.5));
        }
        return List.of();
    }

    private static byte[] concat(List<byte[]> chunks) {
        int total = 0;
        for (byte[] c : chunks) {
            total += c.length;
        }
        byte[] out = new byte[total];
        int o = 0;
        for (byte[] c : chunks) {
            System.arraycopy(c, 0, out, o, c.length);
            o += c.length;
        }
        return out;
    }

    private static byte[] readBytes(Path path, long start, int len) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(path.toFile(), "r");
             FileChannel ch = raf.getChannel()) {
            ByteBuffer bb = ByteBuffer.allocate(len);
            ch.position(start);
            int r = ch.read(bb);
            if (r <= 0) {
                return new byte[0];
            }
            bb.flip();
            byte[] out = new byte[bb.remaining()];
            bb.get(out);
            return out;
        }
    }

    private static String decodeUtf8Lenient(byte[] data) {
        CharsetDecoder dec = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPLACE)
                .onUnmappableCharacter(CodingErrorAction.REPLACE);
        try {
            return dec.decode(java.nio.ByteBuffer.wrap(data)).toString();
        } catch (Exception e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private static List<String> splitAllLines(String s) {
        String[] arr = s.split("\\R", -1);
        return new ArrayList<>(Arrays.asList(arr));
    }
}
