package io.hyun424.openchat.chat.room.summary;

import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.global.role.ConditionalOnRuntimeRole;
import io.hyun424.openchat.global.role.RuntimeCapability;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
@ConditionalOnRuntimeRole(capabilities = RuntimeCapability.AI_WORKER)
public class MockRoomSegmentSummarizer implements RoomSegmentSummarizer {

    @Override
    public RoomSegmentSummary summarize(Long roomId, Long startMessageId, Long endMessageId, List<Message> messages) {
        List<Long> evidenceIds = messages.stream()
                .map(Message::getId)
                .limit(3)
                .toList();
        return new RoomSegmentSummary(
                "읽지 않은 최근 메시지를 요약했어요.",
                evidenceIds,
                List.of()
        );
    }
}
