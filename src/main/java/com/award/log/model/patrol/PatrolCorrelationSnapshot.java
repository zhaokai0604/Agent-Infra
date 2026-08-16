package com.award.log.model.patrol;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 巡检多维关联快照（与 patrol_history.correlation_json 对应）。
 */
public class PatrolCorrelationSnapshot {

    private String timestamp;
    private double diskUsagePct;
    private double cpuUsagePct;
    private double memoryUsagePct;
    private long alarmTotal24h;
    private long alarmErrorApprox;
    private long alarmFatalApprox;
    private long anomalyLogsDay1 = -1;
    private int zombieProcesses;
    private long novelDrainTemplateKinds1h;
    private List<Map<String, Object>> diskHotspotsTop = List.of();
    private Long anomalyBaseline;
    private String anomalyBaselineAction;
    private Long anomalyObserved;

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("timestamp", timestamp);
        m.put("diskUsagePct", diskUsagePct);
        m.put("cpuUsagePct", cpuUsagePct);
        m.put("memoryUsagePct", memoryUsagePct);
        m.put("alarmTotal24h", alarmTotal24h);
        m.put("alarmErrorApprox", alarmErrorApprox);
        m.put("alarmFatalApprox", alarmFatalApprox);
        m.put("anomalyLogsDay1", anomalyLogsDay1);
        m.put("zombieProcesses", zombieProcesses);
        m.put("novelDrainTemplateKinds1h", novelDrainTemplateKinds1h);
        m.put("diskHotspotsTop", diskHotspotsTop);
        if (anomalyBaseline != null) {
            m.put("anomalyBaseline", anomalyBaseline);
        }
        if (anomalyBaselineAction != null) {
            m.put("anomalyBaselineAction", anomalyBaselineAction);
        }
        if (anomalyObserved != null) {
            m.put("anomalyObserved", anomalyObserved);
        }
        return m;
    }

  @SuppressWarnings("unchecked")
    public static PatrolCorrelationSnapshot fromMap(Map<String, Object> raw) {
        PatrolCorrelationSnapshot s = new PatrolCorrelationSnapshot();
        if (raw == null) {
            return s;
        }
        s.timestamp = str(raw.get("timestamp"));
        s.diskUsagePct = num(raw.get("diskUsagePct"));
        s.cpuUsagePct = num(raw.get("cpuUsagePct"));
        s.memoryUsagePct = num(raw.get("memoryUsagePct"));
        s.alarmTotal24h = lng(raw.get("alarmTotal24h"));
        s.alarmErrorApprox = lng(raw.get("alarmErrorApprox"));
        s.alarmFatalApprox = lng(raw.get("alarmFatalApprox"));
        s.anomalyLogsDay1 = lng(raw.get("anomalyLogsDay1"));
        s.zombieProcesses = (int) lng(raw.get("zombieProcesses"));
        s.novelDrainTemplateKinds1h = lng(raw.get("novelDrainTemplateKinds1h"));
        Object hotspots = raw.get("diskHotspotsTop");
        if (hotspots instanceof List<?> list) {
            List<Map<String, Object>> hs = new ArrayList<>();
            for (Object o : list) {
                if (o instanceof Map<?, ?> mm) {
                    hs.add((Map<String, Object>) mm);
                }
            }
            s.diskHotspotsTop = hs;
        }
        if (raw.get("anomalyBaseline") instanceof Number n) {
            s.anomalyBaseline = n.longValue();
        }
        s.anomalyBaselineAction = str(raw.get("anomalyBaselineAction"));
        if (raw.get("anomalyObserved") instanceof Number n) {
            s.anomalyObserved = n.longValue();
        }
        return s;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(String timestamp) {
        this.timestamp = timestamp;
    }

    public void setDiskUsagePct(double diskUsagePct) {
        this.diskUsagePct = diskUsagePct;
    }

    public void setCpuUsagePct(double cpuUsagePct) {
        this.cpuUsagePct = cpuUsagePct;
    }

    public void setMemoryUsagePct(double memoryUsagePct) {
        this.memoryUsagePct = memoryUsagePct;
    }

    public void setAlarmTotal24h(long alarmTotal24h) {
        this.alarmTotal24h = alarmTotal24h;
    }

    public void setAlarmErrorApprox(long alarmErrorApprox) {
        this.alarmErrorApprox = alarmErrorApprox;
    }

    public void setAlarmFatalApprox(long alarmFatalApprox) {
        this.alarmFatalApprox = alarmFatalApprox;
    }

    public void setAnomalyLogsDay1(long anomalyLogsDay1) {
        this.anomalyLogsDay1 = anomalyLogsDay1;
    }

    public void setZombieProcesses(int zombieProcesses) {
        this.zombieProcesses = zombieProcesses;
    }

    public void setNovelDrainTemplateKinds1h(long novelDrainTemplateKinds1h) {
        this.novelDrainTemplateKinds1h = novelDrainTemplateKinds1h;
    }

    public void setDiskHotspotsTop(List<Map<String, Object>> diskHotspotsTop) {
        this.diskHotspotsTop = diskHotspotsTop;
    }

    public void setAnomalyBaseline(Long anomalyBaseline) {
        this.anomalyBaseline = anomalyBaseline;
    }

    public void setAnomalyBaselineAction(String anomalyBaselineAction) {
        this.anomalyBaselineAction = anomalyBaselineAction;
    }

    public void setAnomalyObserved(Long anomalyObserved) {
        this.anomalyObserved = anomalyObserved;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static double num(Object o) {
        return o instanceof Number n ? n.doubleValue() : 0;
    }

    private static long lng(Object o) {
        return o instanceof Number n ? n.longValue() : 0;
    }
}
