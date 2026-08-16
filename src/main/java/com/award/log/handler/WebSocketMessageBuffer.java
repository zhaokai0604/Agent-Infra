package com.award.log.handler;

import com.alibaba.fastjson.JSON;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentLinkedDeque;

/**
 * 最近 N 条 WebSocket 推送，供客户端重连后 sync 补发。
 */
@Component
public class WebSocketMessageBuffer {

    private final ConcurrentLinkedDeque<String> recent = new ConcurrentLinkedDeque<>();
    private final int capacity;

    public WebSocketMessageBuffer(@Value("${ops.websocket.replay-buffer-size:50}") int capacity) {
        this.capacity = Math.min(200, Math.max(10, capacity));
    }

    public void record(Map<String, Object> envelope) {
        if (envelope == null || envelope.isEmpty()) {
            return;
        }
        recent.addLast(JSON.toJSONString(envelope));
        while (recent.size() > capacity) {
            recent.pollFirst();
        }
    }

    public List<String> snapshotJsonMessages() {
        return new ArrayList<>(recent);
    }
}
