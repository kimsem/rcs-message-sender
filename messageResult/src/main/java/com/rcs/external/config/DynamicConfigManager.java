package com.rcs.external.config;

import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.scheduling.annotation.Scheduled;

import java.io.File;
import java.io.FileInputStream;
import java.util.Properties;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
@Getter
public class DynamicConfigManager {
    private final AtomicInteger batchSize = new AtomicInteger(1000);
    private final AtomicInteger maxThreads = new AtomicInteger(10);
    private final AtomicInteger eventHubBatchSize = new AtomicInteger(100);
    private final AtomicInteger successRate = new AtomicInteger(80);

    private static final int MIN_BATCH_SIZE = 100;
    private static final int MAX_BATCH_SIZE = 2000;
    private static final int MIN_THREADS = 1;
    private static final int MAX_THREADS = Runtime.getRuntime().availableProcessors() * 2;
    private static final int MIN_EVENT_HUB_BATCH = 50;
    private static final int MAX_EVENT_HUB_BATCH = 500;
    private static final int MIN_SUCCESS_RATE = 0;
    private static final int MAX_SUCCESS_RATE = 100;

    private final String configPath;

    public DynamicConfigManager() {
        this.configPath = "./config.properties";
        refreshConfiguration();
        log.info("설정 파일 경로: {}", new File(configPath).getAbsolutePath());
    }

    @Scheduled(fixedDelay = 10000) // 10초 간격
    public void refreshConfiguration() {
        try (FileInputStream fis = new FileInputStream(configPath)) {
            Properties props = new Properties();
            props.load(fis);

            updateBatchSize(props);
            updateMaxThreads(props);
            updateEventHubBatchSize(props);
            updateSuccessRate(props);

            log.info("설정 갱신 완료 - batchSize: {}, maxThreads: {}, eventHubBatchSize: {}, successRate: {}",
                    batchSize.get(), maxThreads.get(), eventHubBatchSize.get(), successRate.get());

        } catch (Exception e) {
            log.error("설정 파일 로딩 중 오류 발생: {}", configPath, e);
        }
    }

    private void updateBatchSize(Properties props) {
        try {
            int value = Integer.parseInt(props.getProperty("message.processing.batchSize", "100"));
            batchSize.set(Math.min(Math.max(value, MIN_BATCH_SIZE), MAX_BATCH_SIZE));
        } catch (NumberFormatException e) {
            log.error("batchSize 파싱 오류", e);
        }
    }

    private void updateMaxThreads(Properties props) {
        try {
            int value = Integer.parseInt(props.getProperty("message.processing.maxThreads", "1"));
            maxThreads.set(Math.min(Math.max(value, MIN_THREADS), MAX_THREADS));
        } catch (NumberFormatException e) {
            log.error("maxThreads 파싱 오류", e);
        }
    }

    private void updateEventHubBatchSize(Properties props) {
        try {
            int value = Integer.parseInt(props.getProperty("eventhub.batchSize", "100"));
            eventHubBatchSize.set(Math.min(Math.max(value, MIN_EVENT_HUB_BATCH), MAX_EVENT_HUB_BATCH));
        } catch (NumberFormatException e) {
            log.error("eventHubBatchSize 파싱 오류", e);
        }
    }

    private void updateSuccessRate(Properties props) {
        try {
            int value = Integer.parseInt(props.getProperty("rcs.successRate", "80"));
            successRate.set(Math.min(Math.max(value, MIN_SUCCESS_RATE), MAX_SUCCESS_RATE));
        } catch (NumberFormatException e) {
            log.error("successRate 파싱 오류", e);
        }
    }
}