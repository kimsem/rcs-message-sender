package com.rcs.external.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;
import java.time.LocalDateTime;

@Entity
@Table(name = "message_groups")
@Getter @Setter
public class MessageGroup {
    @Id
    @Column(name = "message_group_id")
    private String messageGroupId;

    @Column(name = "processed_count")
    private Integer processedCount;  // int -> Integer

    @Column(name = "total_count")
    private Integer totalCount;      // int -> Integer

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @Column(name = "brand_id")
    private String brandId;

    @Column(name = "chatbot_id")
    private String chatbotId;

    @Column(name = "master_id")
    private String masterId;

    private String status;

    @Column(name = "template_id")
    private String templateId;
}