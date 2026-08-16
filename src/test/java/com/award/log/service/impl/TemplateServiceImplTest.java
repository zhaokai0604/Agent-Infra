package com.award.log.service.impl;

import com.award.log.mapper.LogTemplateMapper;
import com.award.log.model.LogTemplateRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TemplateServiceImplTest {

    @Mock
    private LogTemplateMapper logTemplateMapper;

    private TemplateServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new TemplateServiceImpl(logTemplateMapper);
    }

    @Test
    void pageShouldDelegateToMapperWithOffset() {
        LogTemplateRecord record = new LogTemplateRecord();
        record.setTemplateId("t1");
        when(logTemplateMapper.selectPage(10, 10)).thenReturn(List.of(record));

        List<LogTemplateRecord> page = service.page(2, 10);

        assertEquals(1, page.size());
        verify(logTemplateMapper).selectPage(10, 10);
    }

    @Test
    void getUpdateAndDeleteShouldUseMapper() {
        LogTemplateRecord record = new LogTemplateRecord();
        record.setTemplateId("t1");
        when(logTemplateMapper.selectByTemplateId("t1")).thenReturn(record);
        when(logTemplateMapper.update(any())).thenReturn(1);
        when(logTemplateMapper.deleteByTemplateId("t1")).thenReturn(1);

        assertSame(record, service.get("t1"));
        assertTrue(service.update("t1", new LogTemplateRecord()));
        assertTrue(service.delete("t1"));
    }

    @Test
    void mergeShouldCombineUseCountsAndDeleteSource() {
        LogTemplateRecord from = new LogTemplateRecord();
        from.setTemplateId("src");
        from.setUseCount(10L);
        LogTemplateRecord target = new LogTemplateRecord();
        target.setTemplateId("dst");
        target.setUseCount(5L);
        when(logTemplateMapper.selectByTemplateId("src")).thenReturn(from);
        when(logTemplateMapper.selectByTemplateId("dst")).thenReturn(target);
        when(logTemplateMapper.update(any())).thenReturn(1);
        when(logTemplateMapper.deleteByTemplateId("src")).thenReturn(1);

        assertTrue(service.merge("src", "dst"));
        assertEquals(15L, target.getUseCount());
        verify(logTemplateMapper).deleteByTemplateId("src");
    }

    @Test
    void mergeReturnsFalseWhenEitherTemplateMissing() {
        when(logTemplateMapper.selectByTemplateId("missing")).thenReturn(null);
        assertFalse(service.merge("missing", "dst"));
    }

    @Test
    void detectTemplateChangeShouldCountHighFrequencyTemplates() {
        LogTemplateRecord low = new LogTemplateRecord();
        low.setUseCount(10L);
        LogTemplateRecord high = new LogTemplateRecord();
        high.setUseCount(2000L);
        when(logTemplateMapper.selectPage(0, 1000)).thenReturn(List.of(low, high));

        Map<String, Object> stat = service.detectTemplateChange();

        assertEquals(2, stat.get("templateCount"));
        assertEquals(1L, stat.get("highFreqCount"));
    }
}
