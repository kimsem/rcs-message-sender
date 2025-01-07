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
import com.rcs.external.dto.MessageResultEvent;
import com.rcs.external.repository.MessageRepository;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Random;
import java.util.concurrent.*;

@Slf4j
@Service
public class MessageProcessingService {
    private final MessageRepository messageRepository;
    private final EventHubProducerClient eventHubProducerClient;
    private final ObjectMapper objectMapper;
    private final BlockingQueue<EventDataBatch> batchQueue;
    private final ExecutorService batchSenderExecutor;
    private final Random random = new Random();
    private volatile boolean isRunning = true;

    private final int batchSize;
    private final int maxThreads;
    private final int eventHubBatchSize;
    private final int successRate;

    public MessageProcessingService(
            MessageRepository messageRepository,
            @Value("${message.processing.default-batch-size:1000}") int batchSize,
            @Value("${message.processing.default-threads:10}") int maxThreads,
            @Value("${message.processing.default-event-hub-batch:100}") int eventHubBatchSize,
            @Value("${message.processing.default-success-rate:80}") int successRate,
            @Value("${eventhub.connection-string}") String eventHubConnectionString) {

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
            long processedCount = 0;
            int pageNumber = 0;
            EventDataBatch currentBatch = createNewBatch();

            // DB 연결 확인
            try {
                messageRepository.count();
            } catch (Exception e) {
                log.error("데이터베이스 연결 오류. 다음 스케줄에서 재시도합니다.", e);
                return;
            }

            // 이벤트 허브 연결 확인
            try {
                eventHubProducerClient.createBatch();
            } catch (Exception e) {
                log.error("이벤트 허브 연결 오류. 다음 스케줄에서 재시도합니다.", e);
                return;
            }


            log.info("메시지 처리 작업을 시작합니다. (batchSize={})", batchSize);

            while (true) {
                Page<Message> messages = messageRepository.findAll(PageRequest.of(pageNumber, batchSize));
                if (messages.isEmpty()) {
                    break;
                }

                for (Message message : messages) {
                    try {
                        MessageResultEvent event = createMessageEvent(message);
                        EventData eventData = new EventData(objectMapper.writeValueAsString(event));

                        if (!currentBatch.tryAdd(eventData)) {
                            batchQueue.put(currentBatch);
                            currentBatch = createNewBatch();
                            if (!currentBatch.tryAdd(eventData)) {
                                throw new RuntimeException("Event too large for empty batch");
                            }
                        }

                        processedCount++;

                        if (processedCount % 1000 == 0) {
                            double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
                            double rate = processedCount / elapsedSeconds;
                            log.info("{} 메시지 처리됨, 처리율: {}/sec",
                                    processedCount,
                                    String.format("%.2f", rate));
                        }
                    } catch (Exception e) {
                        log.error("메시지 처리 중 오류 발생: {}", message.getMessageId(), e);
                    }
                }

                pageNumber++;
                if (!messages.hasNext()) {
                    break;
                }
            }

            if (currentBatch != null && currentBatch.getCount() > 0) {
                batchQueue.put(currentBatch);
            }

            long endTime = System.currentTimeMillis();
            double totalSeconds = (endTime - startTime) / 1000.0;
            double averageRate = processedCount / totalSeconds;

            log.info("메시지 처리 완료 - 총 {} 메시지, 처리 시간: {}초, 평균 처리율: {}/sec",
                    processedCount,
                    String.format("%.2f", totalSeconds),
                    String.format("%.2f", averageRate));

        } catch (Exception e) {
            log.error("메시지 처리 중 오류 발생", e);
        }
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
                log.info("배치 전송 완료 (size: {})", batch.getCount());
                return;
            } catch (Exception e) {
                retryCount++;
                if (retryCount >= maxRetries) {
                    log.error("최대 재시도 횟수 초과", e);
                    break;
                }
                log.warn("배치 전송 실패, 재시도 {}/{}", retryCount, maxRetries);
                try {
                    Thread.sleep(1000 * retryCount);
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