package com.rcs.external.repository;

import com.rcs.external.domain.MessageGroup;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageGroupRepository extends JpaRepository<MessageGroup, String> {
    List<MessageGroup> findByStatus(String status);
}