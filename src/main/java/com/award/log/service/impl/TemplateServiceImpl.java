package com.award.log.service.impl;

import com.award.log.mapper.LogTemplateMapper;
import com.award.log.model.LogTemplateRecord;
import com.award.log.service.TemplateService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Service
public class TemplateServiceImpl implements TemplateService {

    private final LogTemplateMapper logTemplateMapper;

    public TemplateServiceImpl(LogTemplateMapper logTemplateMapper) {
        this.logTemplateMapper = logTemplateMapper;
    }

    @Override
    public List<LogTemplateRecord> page(int pageNum, int pageSize) {
        return logTemplateMapper.selectPage((pageNum - 1) * pageSize, pageSize);
    }

    @Override
    public LogTemplateRecord get(String id) {
        return logTemplateMapper.selectByTemplateId(id);
    }

    @Override
    public boolean update(String id, LogTemplateRecord record) {
        record.setTemplateId(id);
        return logTemplateMapper.update(record) > 0;
    }

    @Override
    public boolean delete(String id) {
        return logTemplateMapper.deleteByTemplateId(id) > 0;
    }

    @Override
    public boolean merge(String id, String targetId) {
        LogTemplateRecord from = get(id);
        LogTemplateRecord target = get(targetId);
        if (from == null || target == null) {
            return false;
        }
        target.setUseCount((target.getUseCount() == null ? 0 : target.getUseCount()) + (from.getUseCount() == null ? 0 : from.getUseCount()));
        logTemplateMapper.update(target);
        logTemplateMapper.deleteByTemplateId(id);
        return true;
    }

    @Override
    public Map<String, Object> detectTemplateChange() {
        List<LogTemplateRecord> records = page(1, 1000);
        long highFreq = records.stream().filter(t -> t.getUseCount() != null && t.getUseCount() > 1000).count();
        return Map.of("templateCount", records.size(), "highFreqCount", highFreq);
    }

    @Scheduled(fixedDelay = 3600000)
    public void checkTemplateBurst() {
        Map<String, Object> stat = detectTemplateChange();
        int templateCount = (Integer) stat.get("templateCount");
        if (templateCount > 50) {
            // 此处可接入现有告警服务
        }
    }
}
