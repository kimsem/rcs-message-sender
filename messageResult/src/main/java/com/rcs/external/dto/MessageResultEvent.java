package com.rcs.external.dto;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class MessageResultEvent {
    private String messageId;
    private String status;
    private String resultCode;
    private String resultMessage;

    public static final String STATUS_SENT = "SENT";
    public static final String STATUS_FAILED = "FAILED";

    public static final String RESULT_CODE_SUCCESS = "RC001";
    public static final String RESULT_CODE_SYSTEM_ERROR = "RC002";
    public static final String RESULT_CODE_INVALID_MESSAGE = "RC003";

    public static final String RESULT_MESSAGE_SUCCESS = "메시지 전송 성공";
    public static final String RESULT_MESSAGE_SYSTEM_ERROR = "시스템 오류";
    public static final String RESULT_MESSAGE_INVALID_MESSAGE = "유효하지 않은 메시지";
}