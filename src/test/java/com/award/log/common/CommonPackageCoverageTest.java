package com.award.log.common;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CommonPackageCoverageTest {

    @Test
    void resultFactoriesCoverSuccessAndErrorPaths() {
        Result<Void> bare = Result.success();
        assertEquals(200, bare.getCode());
        assertEquals("操作成功", bare.getMessage());
        assertNull(bare.getData());

        Result<String> withData = Result.success("payload");
        assertEquals("payload", withData.getData());

        Result<String> withMsg = Result.success("x", "done");
        assertEquals("done", withMsg.getMessage());

        Result<String> err = Result.error("fail");
        assertEquals(500, err.getCode());
        assertEquals("fail", err.getMessage());

        Result<String> errCode = Result.error(403, "denied");
        assertEquals(403, errCode.getCode());
    }

    @Test
    void pageResultSupportsLombokDataContract() {
        PageResult<String> page = new PageResult<>(List.of("a", "b"), 2L);
        assertEquals(2, page.getList().size());
        assertEquals(2L, page.getTotal());

        page.setList(List.of("c"));
        page.setTotal(1L);
        assertEquals(1, page.getList().size());
        assertNotNull(page.toString());
        assertEquals(page, page);
        assertNotEquals(page, new PageResult<>(List.of("c"), 99L));
    }

    @Test
    void globalExceptionHandlerMapsCheckedAndUnchecked() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();

        Result<String> runtime = handler.handleRuntimeException(new RuntimeException("boom"));
        assertEquals(500, runtime.getCode());
        assertEquals("Business processing failed: RuntimeException: boom", runtime.getMessage());

        Result<String> generic = handler.handleException(new Exception("oops"));
        assertEquals(500, generic.getCode());
        assertEquals("Internal server error: Exception: oops", generic.getMessage());
    }
}
