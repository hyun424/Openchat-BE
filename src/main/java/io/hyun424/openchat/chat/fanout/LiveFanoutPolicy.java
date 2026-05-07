package io.hyun424.openchat.chat.fanout;

import io.hyun424.openchat.chat.message.dto.ChatMessageDto;
import io.hyun424.openchat.chat.room.hot.RoomHotState;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

class LiveFanoutPolicy {

    private final boolean enabled;
    private final int hotMaxMessagesPerSecond;
    private final int superHotMaxMessagesPerSecond;
    private final LongSupplier clock;
    private final ConcurrentHashMap<Long, RoomLiveCapState> liveCapStates = new ConcurrentHashMap<>();

    LiveFanoutPolicy(boolean enabled, int hotMaxMessagesPerSecond, int superHotMaxMessagesPerSecond) {
        this(enabled, hotMaxMessagesPerSecond, superHotMaxMessagesPerSecond, System::currentTimeMillis);
    }

    LiveFanoutPolicy(boolean enabled,
                     int hotMaxMessagesPerSecond,
                     int superHotMaxMessagesPerSecond,
                     LongSupplier clock) {
        this.enabled = enabled;
        this.hotMaxMessagesPerSecond = Math.max(1, hotMaxMessagesPerSecond);
        this.superHotMaxMessagesPerSecond = Math.max(1, superHotMaxMessagesPerSecond);
        this.clock = clock;
    }

    LiveBatch apply(Long roomId, List<ChatMessageDto> messages, RoomHotState state) {
        Long lastSequence = sequenceOf(messages.get(messages.size() - 1));
        if (!enabled || state == RoomHotState.NORMAL || state == RoomHotState.WATCHED || state == RoomHotState.WARM) {
            return new LiveBatch(messages, true, 0, lastSequence, false, state);
        }

        int maxMessagesPerSecond = state == RoomHotState.SUPER_HOT
                ? superHotMaxMessagesPerSecond
                : hotMaxMessagesPerSecond;
        RoomLiveCapState capState = liveCapStates.computeIfAbsent(roomId, ignored -> new RoomLiveCapState());
        LivePermit permit = capState.reserve(maxMessagesPerSecond, messages.size(), clock.getAsLong());

        if (permit.visibleCount() <= 0) {
            return new LiveBatch(List.of(), false, permit.omittedCount(), lastSequence, true, state);
        }

        List<ChatMessageDto> visibleMessages = messages.subList(0, permit.visibleCount());
        return new LiveBatch(
                List.copyOf(visibleMessages),
                permit.omittedCount() == 0,
                permit.omittedCount(),
                lastSequence,
                true,
                state
        );
    }

    private Long sequenceOf(ChatMessageDto message) {
        return message.getSequence() != null ? message.getSequence() : message.getId();
    }
}
