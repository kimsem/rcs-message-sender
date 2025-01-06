package com.rcs.external.repository;

import com.rcs.external.domain.Message;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface MessageRepository extends JpaRepository<Message, String> {
    @Query(value = "SELECT m FROM Message m WHERE m.messageGroupId = :groupId")
    Page<Message> findByMessageGroupIdWithPagination(
            @Param("groupId") String messageGroupId,
            Pageable pageable
    );
}