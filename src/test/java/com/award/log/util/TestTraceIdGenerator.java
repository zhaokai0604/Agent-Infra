package com.award.log.util;

public class TestTraceIdGenerator implements TraceIdGenerator {

    private final String id;

    public TestTraceIdGenerator(String id) {
        this.id = id;
    }

    @Override
    public String nextId() {
        return id;
    }
}
