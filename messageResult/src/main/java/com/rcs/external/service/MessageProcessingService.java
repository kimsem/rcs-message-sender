package com.rcs.external.service;

import com.azure.messaging.eventhubs.EventData;
import com.azure.messaging.eventhubs.EventDataBatch;
import com.azure.messaging.eventhubs.EventHubClientBuilder;
import com.azure.messaging.eventhubs.EventHubProducerClient;
import com.azure.messaging.eventhubs.models.CreateBatchOptions;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.rcs.external.domain.Message;
import com.rcs.external.domain.MessageGroup;
import com.rcs.external.dto.MessageResultEvent;
import com.rcs.external.repository.MessageGroupRepository;
import com.rcs.external.repository.MessageRepository;
import com.rcs.external.util.ProcessingMetrics;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.stream.Collectors;

@Slf4j
@Service
public class MessageProcessingService {
    private final MessageGroupRepository messageGroupRepository;
    private final MessageRepository messageRepository;
    private final EventHubProducerClient eventHubProducerClient;
    private final ObjectMapper objectMapper;
    private final BlockingQueue<EventDataBatch> batchQueue;
    private final ExecutorService batchSenderExecutor;
    private final Map<String, ProcessingMetrics> metricsMap = new ConcurrentHashMap<>();
    private final Random random = new Random();
    private volatile boolean isRunning = true;

    private final int batchSize;
    private final int maxThreads;
    private final int eventHubBatchSize;
    private final int successRate;

    public MessageProcessingService(
            MessageGroupRepository messageGroupRepository,
            MessageRepository messageRepository,
            @Value("${message.processing.default-batch-size:1000}") int batchSize,
            @Value("${message.processing.default-threads:10}") int maxThreads,
            @Value("${message.processing.default-event-hub-batch:100}") int eventHubBatchSize,
            @Value("${message.processing.default-success-rate:80}") int successRate,
            @Value("${eventhub.connection-string}") String eventHubConnectionString) {

        this.messageGroupRepository = messageGroupRepository;
        this.messageRepository = messageRepository;
        this.batchSize = batchSize;
        this.maxThreads = maxThreads;
        this.eventHubBatchSize = eventHubBatchSize;
        this.successRate = successRate;

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.batchQueue = new LinkedBlockingQueue<>();
        this.batchSenderExecutor = Executors.newFixedThreadPool(maxThreads);

        this.eventHubProducerClient = new EventHubClientBuilder()
                .connectionString(eventHubConnectionString, "rcs-message-result")
                .buildProducerClient();

        startBatchProcessor();
    }

    @PreDestroy
    public void cleanup() {
        isRunning = false;
        if (batchSenderExecutor != null) {
            batchSenderExecutor.shutdown();
            try {
                if (!batchSenderExecutor.awaitTermination(60, TimeUnit.SECONDS)) {
                    batchSenderExecutor.shutdownNow();
                }
            } catch (InterruptedException e) {
                batchSenderExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (eventHubProducerClient != null) {
            eventHubProducerClient.close();
        }
    }

    private void startBatchProcessor() {
        CompletableFuture.runAsync(() -> {
            while (isRunning) {
                try {
                    EventDataBatch batch = batchQueue.poll(100, TimeUnit.MILLISECONDS);
                    if (batch != null && batch.getCount() > 0) {
                        sendBatchToEventHub(batch);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.error("배치 처리기가 중단되었습니다", e);
                    break;
                } catch (Exception e) {
                    log.error("배치 처리 중 오류 발생", e);
                }
            }
        }, batchSenderExecutor);
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processMessages() {
        try {
            long startTime = System.currentTimeMillis();
            log.info("대량 메시지 처리 작업을 시작합니다. (batchSize={})", batchSize);

            List<MessageGroup> readyGroups = messageGroupRepository.findByStatus("READY");
            if (readyGroups.isEmpty()) {
                return;
            }

            List<CompletableFuture<Void>> futures = readyGroups.stream()
                    .map(this::processGroupAsync)
                    .collect(Collectors.toList());

            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

            long endTime = System.currentTimeMillis();
            log.info("메시지 처리 완료. 소요시간: {}초", (endTime - startTime) / 1000.0);

        } catch (Exception e) {
            log.error("메시지 처리 중 오류 발생", e);
        }
    }

    private CompletableFuture<Void> processGroupAsync(MessageGroup group) {
        return CompletableFuture.runAsync(() -> {
            try {
                log.info("메시지 그룹 {} 처리 시작", group.getMessageGroupId());
                EventDataBatch currentBatch = createNewBatch();
                int processedCount = 0;
                int pageNumber = 0;

                while (true) {
                    List<Message> messages = fetchMessagesInBatch(group.getMessageGroupId(), pageNumber);
                    if (messages.isEmpty()) {
                        break;
                    }

                        for (Message message : messages) {
                            MessageResultEvent event = createMessageEvent(message);
                            EventData eventData = new EventData(objectMapper.writeValueAsString(event));

                            if (!currentBatch.tryAdd(eventData)) {
                                batchQueue.put(currentBatch);
                                currentBatch = createNewBatch();
                                if (!currentBatch.tryAdd(eventData)) {
                                    throw new RuntimeException("Event too large for empty batch");
                                }
                            }
                        }

                        metrics.incrementProcessedCount(messages.size());

                    if (processedCount % 1000 == 0) {
                        log.info("그룹 {} - {} 메시지 처리됨", group.getMessageGroupId(), processedCount);
                    }

                        pageNumber++;
                    }

                    if (currentBatch != null && currentBatch.getCount() > 0) {
                        batchQueue.put(currentBatch);
                    }

                log.info("메시지 그룹 {} 처리 완료 - 총 {} 메시지", group.getMessageGroupId(), processedCount);

                } finally {
                    metricsMap.remove(group.getMessageGroupId());
                }
            } catch (Exception e) {
                log.error("메시지 그룹 {} 처리 중 오류 발생", group.getMessageGroupId(), e);
                throw new RuntimeException(e);
            }
        }, batchSenderExecutor);
    }

    @Transactional
    protected void updateMessageGroupProcessedCount(MessageGroup group, int processedCount) {
        try {
            group.setProcessedCount(processedCount);
            group.setUpdatedAt(LocalDateTime.now());
            messageGroupRepository.save(group);
        } catch (Exception e) {
            log.error("메시지 그룹 처리 수 업데이트 중 오류 발생: {}", group.getMessageGroupId(), e);
        }
    }

    @Transactional
    protected void updateMessageGroupStatus(MessageGroup group, int finalProcessedCount) {
        try {
            group.setProcessedCount(finalProcessedCount);
            group.setStatus("COMPLETED");
            group.setUpdatedAt(LocalDateTime.now());
            messageGroupRepository.save(group);
            log.info("메시지 그룹 {} 상태가 COMPLETED로 업데이트됨", group.getMessageGroupId());
        } catch (Exception e) {
            log.error("메시지 그룹 상태 업데이트 중 오류 발생: {}", group.getMessageGroupId(), e);
        }
    }

    private List<Message> fetchMessagesInBatch(String groupId, int pageNumber) {
        try {
            return messageRepository.findByMessageGroupIdWithPagination(
                    groupId,
                    PageRequest.of(pageNumber, batchSize)
            ).getContent();
        } catch (Exception e) {
            log.error("메시지 조회 중 오류 발생: groupId={}, page={}", groupId, pageNumber, e);
            return Collections.emptyList();
        }
    }

    private CompletableFuture<Void> processMessageAsync(Message message, EventDataBatch batch) {
        return CompletableFuture.runAsync(() -> {
            try {
                MessageResultEvent event = createMessageEvent(message);
                EventData eventData = new EventData(objectMapper.writeValueAsString(event));

                if (!batch.tryAdd(eventData)) {
                    batchQueue.offer(batch);
                    EventDataBatch newBatch = createNewBatch();
                    if (!newBatch.tryAdd(eventData)) {
                        throw new RuntimeException("Event too large for empty batch");
                    }
                }
            } catch (Exception e) {
                log.error("메시지 처리 중 오류: {}", message.getMessageId(), e);
            }
        }, batchSenderExecutor);
    }

    private EventDataBatch createNewBatch() {
        return eventHubProducerClient.createBatch(
                new CreateBatchOptions().setMaximumSizeInBytes(1024 * 1024)
        );
    }

    private void sendBatchToEventHub(EventDataBatch batch) {
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                eventHubProducerClient.send(batch);
                return;
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    log.error("최대 재시도 횟수 초과", e);
                    break;
                }
                log.warn("배치 전송 실패, 재시도 {}/{}", retryCount, maxRetries);
                try {
                    Thread.sleep(1000 * retryCount); // 재시도 간 지연 추가
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        }
    }

    private MessageResultEvent createMessageEvent(Message message) {
        boolean isSuccess = random.nextInt(100) < successRate;

        return MessageResultEvent.builder()
                .messageId(message.getMessageId())
                .status(isSuccess ? MessageResultEvent.STATUS_SENT : MessageResultEvent.STATUS_FAILED)
                .resultCode(isSuccess ? MessageResultEvent.RESULT_CODE_SUCCESS :
                        MessageResultEvent.RESULT_CODE_SYSTEM_ERROR)
                .resultMessage(isSuccess ? MessageResultEvent.RESULT_MESSAGE_SUCCESS :
                        MessageResultEvent.RESULT_MESSAGE_SYSTEM_ERROR)
                .build();
    }
}