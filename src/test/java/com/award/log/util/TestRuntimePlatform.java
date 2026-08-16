package com.award.log.util;

public class TestRuntimePlatform implements RuntimePlatform {

    private final boolean windows;

    public TestRuntimePlatform(boolean windows) {
        this.windows = windows;
    }

    @Override
    public boolean isWindows() {
        return windows;
    }
}
