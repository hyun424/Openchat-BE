package io.hyun424.openchat.chat.room.service;

import io.hyun424.openchat.chat.member.entity.MemberStatus;
import io.hyun424.openchat.chat.member.repository.RoomMemberRepository;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.domain.RoomStatus;
import io.hyun424.openchat.chat.room.dto.MyRoomResponse;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.dto.RoomListResponse;
import io.hyun424.openchat.chat.room.dto.RoomMapResponse;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.StringUtils;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class RoomService {

    private final RoomRepository roomRepository;
    private final RoomMemberRepository roomMemberRepository;
    private final RoomSessionRegistry roomSessionRegistry;

    public Room createRoom(String userId, RoomCreateRequest request) {
        Room.RoomBuilder builder = Room.builder()
                .name(request.getName())
                .ownerId(userId)
                .maxMembers(request.getMaxMembers())
                .requiresApproval(request.getRequiresApproval() != null ? request.getRequiresApproval() : false)
                .description(request.getDescription())
                .rules(request.getRules())
                .imageUrl(request.getImageUrl())
                .category(request.getCategory())
                .lat(request.getLat())
                .lng(request.getLng())
                .locationName(request.getLocationName());

        // 날짜/시간 파싱
        if (StringUtils.hasText(request.getMeetingDate())) {
            builder.meetingDate(LocalDate.parse(request.getMeetingDate()));
        }
        if (StringUtils.hasText(request.getMeetingTime())) {
            builder.meetingTime(LocalTime.parse(request.getMeetingTime()));
        }

        return roomRepository.save(builder.build());
    }

    public List<Room> getRooms() {
        return roomRepository.findAll();
    }

    /**
     * 방 목록 + 현재 인원수 (ACTIVE만, 최신순)
     */
    @Transactional(readOnly = true)
    public List<RoomListResponse> getRoomsWithMemberCount() {
        return roomRepository.findAllByStatusOrderByCreatedAtDesc(RoomStatus.ACTIVE).stream()
                .map(room -> {
                    int currentMembers = roomMemberRepository
                            .countByRoomIdAndLeftAtIsNullAndStatus(room.getId(), MemberStatus.APPROVED);
                    return RoomListResponse.from(room, currentMembers);
                })
                .toList();
    }

    /**
     * 지도용 방 목록 (ACTIVE만, 위치 정보 포함)
     */
    @Transactional(readOnly = true)
    public List<RoomMapResponse> getRoomsForMap() {
        return roomRepository.findAllByStatusAndLatIsNotNullAndLngIsNotNull(RoomStatus.ACTIVE).stream()
                .map(room -> {
                    int currentMembers = roomMemberRepository
                            .countByRoomIdAndLeftAtIsNullAndStatus(room.getId(), MemberStatus.APPROVED);
                    return RoomMapResponse.from(room, currentMembers);
                })
                .toList();
    }

    public Room getRoomOrThrow(Long roomId) {
        return roomRepository.findById(roomId)
                .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
    }

    /**
     * ACTIVE 상태 방만 조회 (종료된 방 접근 차단)
     */
    public Room getActiveRoomOrThrow(Long roomId) {
        Room room = getRoomOrThrow(roomId);
        if (!room.isAccessible()) {
            throw new ApiException(ErrorCode.ROOM_ENDED);
        }
        return room;
    }

    /**
     * 방 삭제 (방장만 가능)
     */
    @Transactional
    public void deleteRoom(Long roomId, String userId) {
        Room room = getRoomOrThrow(roomId);

        // 이미 종료된 방
        if (!room.isAccessible()) {
            throw new ApiException(ErrorCode.ROOM_ENDED);
        }

        // 방장만 삭제 가능
        if (!room.getOwnerId().equals(userId)) {
            throw new ApiException(ErrorCode.NOT_ROOM_OWNER);
        }

        room.delete();
        log.info("[ROOM DELETE] roomId={} by owner={}", roomId, userId);

        // 해당 방의 모든 WebSocket 세션 종료
        roomSessionRegistry.closeAllSessionsInRoom(roomId);
    }

    /**
     * My Chats: returns rooms the user has joined, sorted by most recent activity
     */
    @Transactional(readOnly = true)
    public List<MyRoomResponse> getMyRooms(String userId) {
        return roomRepository.findMyRooms(userId).stream()
                .map(MyRoomResponse::from)
                .toList();
    }

    /**
     * Update room's last message info after a message is successfully ingested.
     * Called by ChatIngestService to keep room metadata in sync.
     *
     * Uses optimistic update - only sets if timestamp is newer (handles multi-server race conditions)
     */
    @Transactional
    public void updateLastMessage(Long roomId, Long timestamp, String message, String senderName) {
        roomRepository.findById(roomId).ifPresent(room -> {
            room.updateLastMessage(timestamp, message, senderName);
            log.debug("[ROOM UPDATE] roomId={} lastMessageAt={} sender={}", roomId, timestamp, senderName);
        });
    }
}
