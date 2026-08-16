package com.award.log.util;

import org.springframework.stereotype.Component;

@Component
public class OsRuntimePlatform implements RuntimePlatform {

    @Override
    public boolean isWindows() {
        return OsRuntime.isWindows();
    }
}
