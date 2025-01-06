package com.rcs.external.service;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rcs.external.config.DynamicConfigManager;
import com.rcs.external.domain.Message;
import com.rcs.external.domain.MessageGroup;
import com.rcs.external.dto.MessageResultEvent;
import com.rcs.external.repository.MessageGroupRepository;
import com.rcs.external.repository.MessageRepository;
import com.rcs.external.util.EventHubRateLimiter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Value;

import jakarta.annotation.PreDestroy;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageProcessingService {
    private final EventHubRateLimiter rateLimiter;
    private final MessageGroupRepository messageGroupRepository;
    private final MessageRepository messageRepository;
    private final EventHubProducerClient eventHubProducerClient;
    private final ObjectMapper objectMapper;
    private final DynamicConfigManager configManager;
    private final Random random = new Random();
    private final ThreadPoolExecutor executor;

    public MessageProcessingService(
            MessageGroupRepository messageGroupRepository,
            MessageRepository messageRepository,
            DynamicConfigManager configManager,
            EventHubRateLimiter rateLimiter,
            @Value("${eventhub.connectionString}") String eventHubConnectionString) {
        this.messageGroupRepository = messageGroupRepository;
        this.messageRepository = messageRepository;
        this.configManager = configManager;
        this.objectMapper = new ObjectMapper();
        this.rateLimiter = rateLimiter; // 초당 100개 메시지로 제한

        this.eventHubProducerClient = new EventHubClientBuilder()
                .connectionString(eventHubConnectionString, "rcs-message-result")
                .buildProducerClient();

        this.executor = new ThreadPoolExecutor(
                configManager.getMaxThreads().get(),
                configManager.getMaxThreads().get(),
                60L, TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(10000),
                new ThreadPoolExecutor.CallerRunsPolicy()
        );
    }

    @PreDestroy
    public void cleanup() {
        // 스레드 풀 정리
        if (executor != null) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(60, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
            } catch (InterruptedException e) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        // Event Hub 클라이언트 정리
        if (eventHubProducerClient != null) {
            eventHubProducerClient.close();
        }
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional(readOnly = true)
    public void processMessages() {
        try {
            adjustThreadPool();

            log.info("대량 메시지 처리 작업을 시작합니다. (batchSize={}, threads={})",
                    configManager.getBatchSize().get(), configManager.getMaxThreads().get());

            List<MessageGroup> readyGroups = messageGroupRepository.findByStatus("READY");
            List<CompletableFuture<Void>> futures = new ArrayList<>();

            for (MessageGroup group : readyGroups) {
                futures.add(processMessageGroup(group));
            }

            // 모든 그룹의 처리 완료 대기
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        } catch (Exception e) {
            log.error("메시지 처리 중 오류 발생", e);
        }
    }

    private synchronized void adjustThreadPool() {
        try {
            int currentPoolSize = executor.getCorePoolSize();
            int desiredPoolSize = Math.max(1, configManager.getMaxThreads().get());  // 최소 1개 보장

            if (currentPoolSize != desiredPoolSize) {
                // 스레드 풀 크기를 안전하게 조정
                if (desiredPoolSize > currentPoolSize) {
                    // 증가시킬 때는 먼저 maximumPoolSize를 늘림
                    executor.setMaximumPoolSize(desiredPoolSize);
                    executor.setCorePoolSize(desiredPoolSize);
                } else {
                    // 감소시킬 때는 먼저 corePoolSize를 줄임
                    executor.setCorePoolSize(desiredPoolSize);
                    executor.setMaximumPoolSize(desiredPoolSize);
                }
                log.info("스레드 풀 크기 조정 완료: {} -> {}", currentPoolSize, desiredPoolSize);
            }
        } catch (IllegalArgumentException e) {
            log.warn("스레드 풀 크기 조정 실패. 현재 크기를 유지합니다.", e);
        }
    }

    private CompletableFuture<Void> processMessageGroup(MessageGroup group) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("메시지 그룹 {} 처리 시작", group.getMessageGroupId());

                int pageNumber = 0;
                int pageSize = configManager.getBatchSize().get();

                while (true) {
                    Page<Message> messagePage = messageRepository.findByMessageGroupIdWithPagination(
                            group.getMessageGroupId(),
                            PageRequest.of(pageNumber, pageSize)
                    );

                    if (!messagePage.hasContent()) {
                        break;
                    }

                    processMessageBatch(messagePage.getContent());

                    if (!messagePage.hasNext()) {
                        break;
                    }
                    pageNumber++;
                }

                log.info("메시지 그룹 {} 처리 완료", group.getMessageGroupId());
            } catch (Exception e) {
                log.error("메시지 그룹 {} 처리 중 오류 발생", group.getMessageGroupId(), e);
            }
        }, executor);
    }

    private void processMessageBatch(List<Message> messages) {
        try {
            int eventHubBatchSize = configManager.getEventHubBatchSize().get();
            List<MessageResultEvent> events = messages.stream()
                    .map(this::createMessageEvent)
                    .collect(Collectors.toList());

            // Event Hub 배치 크기로 분할하여 전송
            for (int i = 0; i < events.size(); i += eventHubBatchSize) {
                int end = Math.min(events.size(), i + eventHubBatchSize);
                log.info("Event Hub 배치 크기로 분할하여 전송",end);
                sendEventsToEventHub(events.subList(i, end));

            }
        } catch (Exception e) {
            log.error("배치 처리 중 오류 발생", e);
        }
    }

    private MessageResultEvent createMessageEvent(Message message) {
        boolean isSuccess = random.nextInt(100) < configManager.getSuccessRate().get();

        return MessageResultEvent.builder()
                .messageId(message.getMessageId())
                .status(isSuccess ? MessageResultEvent.STATUS_SENT : MessageResultEvent.STATUS_FAILED)
                .resultCode(isSuccess ? MessageResultEvent.RESULT_CODE_SUCCESS :
                        MessageResultEvent.RESULT_CODE_SYSTEM_ERROR)
                .resultMessage(isSuccess ? MessageResultEvent.RESULT_MESSAGE_SUCCESS :
                        MessageResultEvent.RESULT_MESSAGE_SYSTEM_ERROR)
                .build();
    }

    private void sendEventsToEventHub(List<MessageResultEvent> events) {
        // Rate limiting 적용
        rateLimiter.acquire(events.size());
        int maxRetries = 3;
        int baseWaitTimeMs = 4000; // 에러 메시지에서 제안한 4초

        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                List<EventData> eventDataList = events.stream()
                        .map(event -> {
                            try {
                                return new EventData(objectMapper.writeValueAsString(event));
                            } catch (Exception e) {
                                log.error("이벤트 변환 중 오류", e);
                                return null;
                            }
                        })
                        .filter(Objects::nonNull)
                        .collect(Collectors.toList());

                if (!eventDataList.isEmpty()) {
                    log.info("이벤트 허브 전송 중");
                    eventHubProducerClient.send(eventDataList);
                    return; // 성공하면 메소드 종료
                }
            } catch (Exception e) {
                String errorMessage = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
                if (errorMessage.contains("server-busy") || errorMessage.contains("throttled")) {
                    if (attempt < maxRetries) {
                        int waitTime = baseWaitTimeMs * (attempt + 1); // 점진적으로 대기 시간 증가
                        log.warn("Event Hub 제한 감지. {}ms 후 재시도 ({}/{})",
                                waitTime, attempt + 1, maxRetries);
                        try {
                            Thread.sleep(waitTime);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw new RuntimeException("이벤트 전송 중단", ie);
                        }
                    } else {
                        log.error("최대 재시도 횟수 초과. 이벤트 전송 실패", e);
                        throw new RuntimeException("이벤트 전송 실패", e);
                    }
                } else {
                    log.error("이벤트 허브 전송 중 오류", e);
                    throw new RuntimeException("이벤트 전송 실패", e);
                }
            }
        }
    }
}