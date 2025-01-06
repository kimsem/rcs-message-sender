package com.rcs.external.util;

import com.google.common.util.concurrent.RateLimiter;
import org.springframework.stereotype.Component;

@Component
public class EventHubRateLimiter {
    private final RateLimiter rateLimiter;

    public EventHubRateLimiter() {
        // 초당 처리량을 3000으로 증가
        this.rateLimiter = RateLimiter.create(3000.0);
    }

    public void acquire(int permits) {
        rateLimiter.acquire(permits);
    }
}