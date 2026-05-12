package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;

import java.util.List;

public interface RoomSegmentSummarizer {
    RoomSegmentSummary summarize(Long roomId, Long startMessageId, Long endMessageId, List<Message> messages);
}
