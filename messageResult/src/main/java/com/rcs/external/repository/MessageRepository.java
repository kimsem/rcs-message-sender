package com.rcs.external.repository;

import com.rcs.external.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    // 상태별 메시지 조회 (페이징)
    Page<Message> findByStatus(String status, Pageable pageable);

    // 상태 업데이트
    @Modifying
    @Query("UPDATE Message m SET m.status = :status WHERE m.messageId IN :messageIds")
    void updateMessagesStatus(@Param("messageIds") List<String> messageIds, @Param("status") String status);
}