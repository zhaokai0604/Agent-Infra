package com.award.log.util;

import org.springframework.stereotype.Component;

@Component
public class SystemTimeSource implements TimeSource {

    @Override
    public long currentTimeMillis() {
        return System.currentTimeMillis();
    }
}
