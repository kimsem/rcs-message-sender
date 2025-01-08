package com.rcs.external.repository;

import com.rcs.external.domain.Message;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.stream.Stream;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    @Query("SELECT m FROM Message m WHERE m.status = :status ORDER BY m.messageId")
    Stream<Message> findByStatusOrderByMessageId(@Param("status") String status);

    @Modifying
    @Query("UPDATE Message m SET m.status = :status WHERE m.messageId IN :messageIds")
    void updateMessagesStatus(@Param("messageIds") List<String> messageIds, @Param("status") String status);
}