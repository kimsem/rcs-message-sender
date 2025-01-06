package com.rcs.external.util;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Component;

@Component
public class EventHubRateLimiter {
    private final RateLimiter rateLimiter;

    public EventHubRateLimiter() {
        // 초당 처리할 메시지 수 (필요에 따라 조정)
        this.rateLimiter = RateLimiter.create(100.0);
    }

    public void acquire(int permits) {
        rateLimiter.acquire(permits);
    }
}