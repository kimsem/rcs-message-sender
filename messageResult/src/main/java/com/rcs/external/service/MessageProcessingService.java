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

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Random;
import java.util.concurrent.*;
import java.util.stream.Stream;

@Slf4j
@Service
public class MessageProcessingService {
    private static final String STATUS_PENDING = "PENDING";
    private static final String STATUS_SENDING = "SENDING";

    private final MessageRepository messageRepository;
    private final EventHubProducerClient eventHubProducerClient;
    private final ObjectMapper objectMapper;
    private final Random random = new Random();

    private final int batchSize;
    private final int successRate;
    private final int updateBatchSize;

    public MessageProcessingService(
            MessageRepository messageRepository,
            @Value("${message.processing.default-batch-size:5000}") int batchSize,
            @Value("${message.processing.default-success-rate:80}") int successRate,
            @Value("${message.processing.update-batch-size:1000}") int updateBatchSize,
            @Value("${eventhub.connection-string}") String eventHubConnectionString) {

        this.messageRepository = messageRepository;
        this.batchSize = batchSize;
        this.successRate = successRate;
        this.updateBatchSize = updateBatchSize;

        this.objectMapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        this.eventHubProducerClient = new EventHubClientBuilder()
                .connectionString(eventHubConnectionString, "rcs-message-result")
                .buildProducerClient();
    }

    @Scheduled(fixedDelay = 5000)
    @Transactional
    public void processMessages() {
        try {
            long startTime = System.currentTimeMillis();
            long processedCount = 0;

            // DB 및 이벤트 허브 연결 확인
            if (!checkConnections()) {
                return;
            }

            log.info("PENDING 상태의 메시지 처리 작업을 시작합니다. (batchSize={})", batchSize);

            List<String> messageIds = new ArrayList<>(updateBatchSize);
            EventDataBatch currentBatch = createNewBatch();

            try (Stream<Message> messageStream = messageRepository.findByStatusOrderByMessageId(STATUS_PENDING)) {
                Iterator<Message> iterator = messageStream.iterator();

                while (iterator.hasNext()) {
                    Message message = iterator.next();
                    try {
                        MessageResultEvent event = createMessageEvent(message);
                        EventData eventData = new EventData(objectMapper.writeValueAsString(event));

                        if (!currentBatch.tryAdd(eventData)) {
                            // 현재 배치가 가득 찼을 때
                            if (sendBatchToEventHub(currentBatch)) {
                                updateMessageStatuses(messageIds);
                            }
                            messageIds.clear();

                            currentBatch = createNewBatch();
                            if (!currentBatch.tryAdd(eventData)) {
                                throw new RuntimeException("Event too large for empty batch");
                            }
                        }

                        messageIds.add(message.getMessageId());
                        processedCount++;

                        // 업데이트 배치 크기에 도달하면 상태 업데이트
                        if (messageIds.size() >= updateBatchSize) {
                            if (sendBatchToEventHub(currentBatch)) {
                                updateMessageStatuses(messageIds);
                            }
                            messageIds.clear();
                            currentBatch = createNewBatch();
                        }

                        if (processedCount % 1000 == 0) {
                            logProgress(processedCount, startTime);
                        }
                    } catch (Exception e) {
                        log.error("메시지 처리 중 오류 발생: {}", message.getMessageId(), e);
                    }
                }
            }

            // 남은 메시지 처리
            if (!messageIds.isEmpty()) {
                if (sendBatchToEventHub(currentBatch)) {
                    updateMessageStatuses(messageIds);
                }
            }

            logCompletion(processedCount, startTime);

        } catch (Exception e) {
            log.error("메시지 처리 중 오류 발생", e);
        }
    }

    private void updateMessageStatuses(List<String> messageIds) {
        try {
            messageRepository.updateMessagesStatus(new ArrayList<>(messageIds), STATUS_SENDING);
        } catch (Exception e) {
            log.error("메시지 상태 업데이트 중 오류 발생. messageIds: {}", messageIds.size(), e);
            throw e;
        }
    }

    private void logProgress(long processedCount, long startTime) {
        double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
        double rate = processedCount / elapsedSeconds;
        log.info("{} 메시지 처리됨, 처리율: {}/sec",
                processedCount,
                String.format("%.2f", rate));
    }

    private void logCompletion(long processedCount, long startTime) {
        double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
        double averageRate = processedCount / totalSeconds;
        log.info("메시지 처리 완료 - 총 {} 메시지, 처리 시간: {}초, 평균 처리율: {}/sec",
                processedCount,
                String.format("%.2f", totalSeconds),
                String.format("%.2f", averageRate));
    }

    private boolean sendBatchToEventHub(EventDataBatch batch) {
        int retryCount = 0;
        int maxRetries = 3;

        while (retryCount < maxRetries) {
            try {
                eventHubProducerClient.send(batch);
                log.info("배치 전송 완료 (size: {})", batch.getCount());
                return true;
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
                    return false;
                }
            }
        }
        return false;
    }

    private EventDataBatch createNewBatch() {
        return eventHubProducerClient.createBatch(
                new CreateBatchOptions().setMaximumSizeInBytes(1024 * 1024)
        );
    }

    private boolean checkConnections() {
        try {
            messageRepository.count();
            eventHubProducerClient.createBatch();
            return true;
        } catch (Exception e) {
            log.error("연결 확인 중 오류 발생. 다음 스케줄에서 재시도합니다.", e);
            return false;
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

    @PreDestroy
    public void cleanup() {
        if (eventHubProducerClient != null) {
            eventHubProducerClient.close();
        }
    }
}