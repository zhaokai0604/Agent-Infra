package com.award.log.security.effect;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 会话/操作者风险预算：限制时间窗内真实写次数与累计不可逆分，避免连续“小操作”积累成事故。
 */
@Service
public class SessionRiskBudgetService {

    private final long windowMs;
    private final int maxRealWrites;
    private final int maxIrreversibilityPoints;
    private final ConcurrentHashMap<String, Deque<BudgetEvent>> eventsBySubject = new ConcurrentHashMap<>();

    public SessionRiskBudgetService(
            @Value("${agent.security.risk-budget.window-ms:3600000}") long windowMs,
            @Value("${agent.security.risk-budget.max-real-writes:20}") int maxRealWrites,
            @Value("${agent.security.risk-budget.max-irreversibility-points:60}") int maxIrreversibilityPoints) {
        this.windowMs = Math.max(60_000L, windowMs);
        this.maxRealWrites = Math.max(1, maxRealWrites);
        this.maxIrreversibilityPoints = Math.max(1, maxIrreversibilityPoints);
    }

    public BudgetDecision check(String subject, ToolEffect effect) {
        if (effect == null || !effect.writeEffect()) {
            return BudgetDecision.allow(snapshot(subject));
        }
        String key = normalize(subject);
        purgeAndGet(key);
        Snapshot snap = snapshot(key);
        if (snap.realWrites() >= maxRealWrites) {
            return BudgetDecision.block(
                    "RISK_BUDGET_WRITES",
                    "会话风险预算耗尽：时间窗内真实写次数已达上限 " + maxRealWrites,
                    snap);
        }
        if (snap.irreversibilityPoints() + effect.irreversibility() > maxIrreversibilityPoints) {
            return BudgetDecision.block(
                    "RISK_BUDGET_IRREVERSIBILITY",
                    "会话风险预算耗尽：累计不可逆分将超过上限 " + maxIrreversibilityPoints,
                    snap);
        }
        return BudgetDecision.allow(snap);
    }

    public void consume(String subject, ToolEffect effect) {
        if (effect == null || !effect.writeEffect()) {
            return;
        }
        String key = normalize(subject);
        Deque<BudgetEvent> q = purgeAndGet(key);
        q.addLast(new BudgetEvent(System.currentTimeMillis(), effect.irreversibility()));
    }

    public Map<String, Object> summary(String subject) {
        Snapshot snap = snapshot(subject);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("realWrites", snap.realWrites());
        m.put("irreversibilityPoints", snap.irreversibilityPoints());
        m.put("maxRealWrites", maxRealWrites);
        m.put("maxIrreversibilityPoints", maxIrreversibilityPoints);
        m.put("windowMs", windowMs);
        return m;
    }

    private Snapshot snapshot(String subject) {
        Deque<BudgetEvent> q = purgeAndGet(normalize(subject));
        int writes = q.size();
        int points = 0;
        for (BudgetEvent e : q) {
            points += e.irreversibility();
        }
        return new Snapshot(writes, points);
    }

    private Deque<BudgetEvent> purgeAndGet(String key) {
        long cutoff = System.currentTimeMillis() - windowMs;
        Deque<BudgetEvent> q = eventsBySubject.computeIfAbsent(key, k -> new ArrayDeque<>());
        synchronized (q) {
            while (!q.isEmpty() && q.peekFirst().atMs() < cutoff) {
                q.removeFirst();
            }
            return q;
        }
    }

    private static String normalize(String subject) {
        if (subject == null || subject.isBlank()) {
            return "anonymous";
        }
        return subject.trim();
    }

    private record BudgetEvent(long atMs, int irreversibility) {
    }

    public record Snapshot(int realWrites, int irreversibilityPoints) {
    }

    public record BudgetDecision(boolean allowed, String code, String message, Snapshot snapshot) {
        public static BudgetDecision allow(Snapshot snapshot) {
            return new BudgetDecision(true, "", "", snapshot);
        }

        public static BudgetDecision block(String code, String message, Snapshot snapshot) {
            return new BudgetDecision(false, code, message, snapshot);
        }
    }
}
