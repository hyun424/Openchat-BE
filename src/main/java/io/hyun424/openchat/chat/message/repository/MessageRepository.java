package io.hyun424.openchat.chat.message.repository;

import io.hyun424.openchat.chat.message.entity.Message;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface MessageRepository extends JpaRepository<Message, Long> {

    /**
     * Recent messages (newest first) - for initial load with lazy scroll
     * Compound sort: createdAt DESC, id DESC guarantees stable ordering
     */
    List<Message> findTop50ByRoomIdOrderByCreatedAtDescIdDesc(Long roomId);

    /**
     * Messages since user joined (oldest first) - for chat display
     * Compound sort: createdAt ASC, id ASC guarantees stable ordering
     * even when multiple messages have the same millisecond timestamp
     */
    List<Message> findByRoomIdAndCreatedAtGreaterThanEqualOrderByCreatedAtAscIdAsc(
            Long roomId,
            Long joinedAt
    );
}

