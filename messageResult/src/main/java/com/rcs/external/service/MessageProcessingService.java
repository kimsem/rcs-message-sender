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
import java.util.List;
import java.util.Random;

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
    public void processMessages() {
        try {
            long startTime = System.currentTimeMillis();
            long processedCount = 0;
            long sentCount = 0;

            if (!checkConnections()) {
                return;
            }

            log.info("PENDING 상태의 메시지 처리 작업을 시작합니다. (batchSize={})", batchSize);

            int pageNumber = 0;
            Page<Message> messagePage;

            do {
                messagePage = getPagedMessages(pageNumber);
                if (!messagePage.isEmpty()) {
                    ProcessingResult result = processMessageBatch(messagePage.getContent(), startTime, processedCount, sentCount);
                    processedCount = result.processedCount;
                    sentCount = result.sentCount;
                }
                pageNumber++;
            } while (messagePage.hasNext());

            logCompletion(processedCount, sentCount, startTime);

        } catch (Exception e) {
            log.error("메시지 처리 중 오류 발생", e);
        }
    }

    @Transactional(readOnly = true)
    protected Page<Message> getPagedMessages(int pageNumber) {
        try {
            return messageRepository.findByStatus(STATUS_PENDING, PageRequest.of(pageNumber, batchSize));
        } catch (Exception e) {
            log.error("메시지 조회 중 오류 발생. pageNumber: {}", pageNumber, e);
            throw e;
        }
    }

    private ProcessingResult processMessageBatch(List<Message> messages, long startTime,
                                                 long currentProcessedCount, long currentSentCount) {
        List<String> messageIds = new ArrayList<>(updateBatchSize);
        EventDataBatch currentBatch = null;
        long processedCount = currentProcessedCount;
        long sentCount = currentSentCount;

        try {
            currentBatch = createNewBatch();

            for (Message message : messages) {
                try {
                    MessageResultEvent event = createMessageEvent(message);
                    EventData eventData = new EventData(objectMapper.writeValueAsString(event));
                    processedCount++;

                    if (currentBatch == null || !currentBatch.tryAdd(eventData)) {
                        if (currentBatch != null) {
                            if (processBatchWithTransaction(currentBatch, messageIds)) {
                                sentCount += currentBatch.getCount();
                            }
                            messageIds.clear();
                        }
                        currentBatch = createNewBatch();
                        if (!currentBatch.tryAdd(eventData)) {
                            log.error("이벤트가 최대 배치 크기를 초과합니다: {}", message.getMessageId());
                            continue;
                        }
                    }

                    messageIds.add(message.getMessageId());

                    if (messageIds.size() >= updateBatchSize) {
                        if (processBatchWithTransaction(currentBatch, messageIds)) {
                            sentCount += currentBatch.getCount();
                        }
                        messageIds.clear();
                        currentBatch = createNewBatch();
                    }

                    if (processedCount % 1000 == 0) {
                        logProgress(processedCount, sentCount, startTime);
                    }
                } catch (Exception e) {
                    log.error("메시지 처리 중 오류 발생: {}", message.getMessageId(), e);
                }
            }

            // 남은 메시지 처리
            if (!messageIds.isEmpty() && currentBatch != null) {
                if (processBatchWithTransaction(currentBatch, messageIds)) {
                    sentCount += currentBatch.getCount();
                }
            }
        } finally {
            currentBatch = null;
        }

        return new ProcessingResult(processedCount, sentCount);
    }

    @Transactional
    protected boolean processBatchWithTransaction(EventDataBatch batch, List<String> messageIds) {
        try {
            if (sendBatchToEventHub(batch)) {
                updateMessageStatuses(messageIds);
                log.info("배치 전송 완료 - 전송건수: {}", batch.getCount());
                return true;
            }
            return false;
        } finally {
            if (batch != null) {
                batch = null;
            }
        }
    }

    @Transactional  // 이 메소드에도 추가
    private void updateMessageStatuses(List<String> messageIds) {
        try {
            messageRepository.updateMessagesStatus(messageIds, STATUS_SENDING);
        } catch (Exception e) {
            log.error("메시지 상태 업데이트 중 오류 발생. messageIds: {}", messageIds.size(), e);
            throw e;
        }
    }

    private boolean sendBatchToEventHub(EventDataBatch batch) {
        if (batch == null || batch.getCount() == 0) {
            return false;
        }

        int retryCount = 0;
        int maxRetries = 5;

        while (retryCount < maxRetries) {
            try {
                eventHubProducerClient.send(batch);
                log.debug("이벤트 허브 전송 성공 - 배치 크기: {}, 배치 bytes: {}",
                        batch.getCount(), batch.getSizeInBytes());
                return true;
            } catch (Exception e) {
                retryCount++;

                if ( e.getMessage().contains("50002")){
                    log.warn("이벤트 허브 처리량 제한 발생 - 4초 대기 후 재시도 ({}/{})",
                            retryCount, maxRetries);
                    try {
                        Thread.sleep(4000); // 4초 대기
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }else{
                    log.error("이벤트 허브 전송 실패 (시도 {}/{}) - 오류: {}",
                            retryCount, maxRetries, e.getMessage());

                    if (retryCount >= maxRetries) {
                        break;
                    }

                    try {
                        Thread.sleep((long) (1000 * Math.pow(2, retryCount)));
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        return false;
                    }
                }
                if ( retryCount >= maxRetries ){
                    break;
                }

            }
        }
        return false;
    }

    private EventDataBatch createNewBatch() {
        return eventHubProducerClient.createBatch(
                new CreateBatchOptions()
                        .setMaximumSizeInBytes(1024 * 1024));
    }

    private boolean checkConnections() {
        try {
            // 단순 count 쿼리 실행
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

    private void logProgress(long processedCount, long sentCount, long startTime) {
        double elapsedSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
        double rate = processedCount / elapsedSeconds;
        log.info("처리현황 - 처리건수: {}, 전송건수: {}, 처리율: {}/sec",
                processedCount,
                sentCount,
                String.format("%.2f", rate));
    }

    private void logCompletion(long processedCount, long sentCount, long startTime) {
        double totalSeconds = (System.currentTimeMillis() - startTime) / 1000.0;
        double averageRate = processedCount / totalSeconds;
        log.info("[처리 완료] - 총 처리건수: {}, 총 전송건수: {}, 처리 시간: {}초, 평균 처리율: {}/sec",
                processedCount,
                sentCount,
                String.format("%.2f", totalSeconds),
                String.format("%.2f", averageRate));
    }

    @PreDestroy
    public void cleanup() {
        if (eventHubProducerClient != null) {
            try {
                eventHubProducerClient.close();
            } catch (Exception e) {
                log.error("EventHub Producer 종료 중 오류 발생", e);
            }
        }
    }

    private static class ProcessingResult {
        final long processedCount;
        final long sentCount;

        ProcessingResult(long processedCount, long sentCount) {
            this.processedCount = processedCount;
            this.sentCount = sentCount;
        }
    }
}