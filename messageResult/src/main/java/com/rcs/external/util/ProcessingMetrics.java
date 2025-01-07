package com.rcs.external.util;

import lombok.Getter;

@Getter
public class ProcessingMetrics {
    private final long startTime;
    private long endTime;
    private int processedCount;
    private final int totalCount;

    public ProcessingMetrics(int totalCount) {
        this.startTime = System.currentTimeMillis();
        this.totalCount = totalCount;
        this.processedCount = 0;
    }

    public void complete() {
        this.endTime = System.currentTimeMillis();
    }

    public double getDurationInSeconds() {
        return (endTime - startTime) / 1000.0;
    }

    public void incrementProcessedCount(int count) {
        this.processedCount += count;
    }

    public String getProgressPercentage() {
        return String.format("%.2f%%", (processedCount * 100.0) / totalCount);
    }

    public int getMessagesPerSecond() {
        if (endTime == 0) return 0;
        double seconds = getDurationInSeconds();
        return seconds > 0 ? (int)(processedCount / seconds) : 0;
    }

    public double getCurrentDurationInSeconds() {
        return (System.currentTimeMillis() - startTime) / 1000.0;
    }
}