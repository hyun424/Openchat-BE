package io.hyun424.openchat.chat.message.repository;

import io.hyun424.openchat.chat.message.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    // 최근 메시지 조회 (최대 N개)
    List<Message> findTop50ByRoomIdOrderByCreatedAtDesc(Long roomId);


    List<Message> findByRoomIdAndCreatedAtAfterOrderByCreatedAtAsc(
            Long roomId,
            Instant joinedAt   // ✅ 통일
    );

}

