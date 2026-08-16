package com.award.log.service;

import com.award.log.mapper.LogAnalysisDetailMapper;
import com.award.log.model.LogAnalysisDetail;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class LogQueryFallbackServiceTest {

    @Mock
    private LogAnalysisDetailMapper detailMapper;

    @InjectMocks
    private LogQueryFallbackService service;

    @Test
    void queryRecentShouldClampAndReturnRows() {
        LogAnalysisDetail row = new LogAnalysisDetail();
        when(detailMapper.selectRecentDetails(eq(7), eq("ERROR"), eq("timeout"), eq(true), eq(100)))
                .thenReturn(List.of(row));

        List<LogAnalysisDetail> out = service.queryRecent(7, "ERROR", "timeout", true, 100);
        assertEquals(1, out.size());
    }

    @Test
    void queryRecentShouldReturnEmptyOnFailure() {
        when(detailMapper.selectRecentDetails(anyInt(), any(), any(), any(), anyInt()))
                .thenThrow(new RuntimeException("db down"));

        assertTrue(service.queryRecent(1, null, null, null, 10).isEmpty());
    }
}
