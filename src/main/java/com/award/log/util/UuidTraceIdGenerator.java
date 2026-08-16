package com.award.log.util;

import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class UuidTraceIdGenerator implements TraceIdGenerator {

    @Override
    public String nextId() {
        return UUID.randomUUID().toString();
    }
}
