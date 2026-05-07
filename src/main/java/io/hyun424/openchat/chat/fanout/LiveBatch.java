package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.hot.RoomHotState;

import java.util.List;

record LiveBatch(List<ChatMessageDto> messages,
                 boolean realtimeComplete,
                 int omittedCount,
                 Long lastSequence,
                 boolean controlled,
                 RoomHotState state) {
}
