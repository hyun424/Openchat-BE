package io.hyun424.openchat.chat.room.read;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.message.entity.Message;
import io.hyun424.openchat.chat.message.repository.MessageRepository;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;

@Service
@RequiredArgsConstructor
public class RoomReadPositionService {

    private final RoomReadPositionRepository readPositionRepository;
    private final MessageRepository messageRepository;
    private final RoomMemberService roomMemberService;

    @Transactional(readOnly = true)
    public RoomReadPositionResponse get(Long roomId, String userId) {
        roomMemberService.getJoinedAtMillis(roomId, userId);
        return readPositionRepository.findByRoomIdAndUserId(roomId, userId)
                .map(RoomReadPositionResponse::from)
                .orElseGet(() -> RoomReadPositionResponse.empty(roomId, userId));
    }

    @Transactional
    public RoomReadPositionResponse update(Long roomId, String userId, Long lastReadMessageId) {
        long joinedAt = roomMemberService.getJoinedAtMillis(roomId, userId);
        Message message = messageRepository.findByIdAndRoomId(lastReadMessageId, roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REQUEST, "읽음 위치로 지정할 수 없는 메시지입니다."));
        if (!roomId.equals(message.getRoomId())) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "다른 방 메시지는 읽음 위치로 지정할 수 없습니다.");
        }
        Long messageId = message.getId();
        if (message.getCreatedAt() < joinedAt) {
            throw new ApiException(ErrorCode.INVALID_REQUEST, "입장 이전 메시지는 읽음 위치로 지정할 수 없습니다.");
        }

        RoomReadPosition position = readPositionRepository.findByRoomIdAndUserId(roomId, userId)
                .orElse(null);
        if (position == null) {
            return RoomReadPositionResponse.from(readPositionRepository.save(
                    RoomReadPosition.create(roomId, userId, messageId, Instant.now())
            ));
        }
        if (position.advanceTo(messageId, Instant.now())) {
            position = readPositionRepository.save(position);
        }
        return RoomReadPositionResponse.from(position);
    }
}
