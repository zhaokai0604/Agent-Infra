package com.award.log.util;

public class TestTimeSource implements TimeSource {

    private long now;

    public TestTimeSource(long now) {
        this.now = now;
    }

    @Override
    public long currentTimeMillis() {
        return now;
    }

    public void setNow(long now) {
        this.now = now;
    }
}
