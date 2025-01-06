package com.rcs.external.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "messages")
@Getter @Setter
public class Message {
    @Id
    @Column(name = "message_id")
    private String messageId;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    private String content;

    @Column(name = "message_group_id")
    private String messageGroupId;

    @Column(name = "recipient_id")
    private String recipientId;

    private String status;
}