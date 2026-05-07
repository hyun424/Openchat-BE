package io.hyun424.openchat.chat.room.service;

import io.hyun424.openchat.chat.metrics.ChatPipelineMetrics;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.domain.RoomStatus;
import io.hyun424.openchat.chat.room.dto.MyRoomResponse;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.dto.RoomListResponse;
import io.hyun424.openchat.chat.room.dto.RoomMapResponse;
import io.hyun424.openchat.chat.room.lifecycle.RoomLifecyclePublisher;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionStateService;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import io.hyun424.openchat.chat.room.repository.RoomRepository;
import io.hyun424.openchat.chat.room.shard.RoomShardAssignmentService;
import io.hyun424.openchat.infra.websocket.session.RoomSessionRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
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
    private final RoomSessionRegistry roomSessionRegistry;
    private final RoomLifecyclePublisher roomLifecyclePublisher;
    private final RoomAfterCommitExecutor afterCommitExecutor;
    private final ChatPipelineMetrics chatPipelineMetrics;
    private final RoomShardAssignmentService roomShardAssignmentService;
    private final RoomPartitionStateService roomPartitionStateService;

    @Transactional
    public Room createRoom(String userId, RoomCreateRequest request) {
        int shardId = roomShardAssignmentService.assignShardForNewRoom();
        Room.RoomBuilder builder = Room.builder()
                .name(request.getName())
                .ownerId(userId)
                .shardId(shardId)
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

        Room room = roomRepository.save(builder.build());
        roomPartitionStateService.ensureInitialized(room.getId());
        return room;
    }

    /**
     * 방 목록 + 현재 인원수 (ACTIVE만, 최신순, 페이지네이션)
     * LEFT JOIN 집계로 N+1 문제 해결
     */
    @Transactional(readOnly = true)
    public Page<RoomListResponse> getRoomsWithMemberCount(int page, int size) {
        return roomRepository.findRoomsWithMemberCount(RoomStatus.ACTIVE, PageRequest.of(page, size))
                .map(RoomListResponse::from);
    }

    /**
     * 지도용 방 목록 (ACTIVE만, 위치 정보 포함, bounding box 필터)
     * LEFT JOIN 집계로 N+1 문제 해결
     */
    @Transactional(readOnly = true)
    public List<RoomMapResponse> getRoomsForMap(
            java.math.BigDecimal swLat, java.math.BigDecimal swLng,
            java.math.BigDecimal neLat, java.math.BigDecimal neLng) {
        return roomRepository.findRoomsForMapWithMemberCount(
                RoomStatus.ACTIVE, swLat, swLng, neLat, neLng).stream()
                .map(RoomMapResponse::from)
                .toList();
    }

    public Room getRoomOrThrow(Long roomId) {
        long startNanos = System.nanoTime();
        try {
            return roomRepository.findById(roomId)
                    .orElseThrow(() -> new ApiException(ErrorCode.ROOM_NOT_FOUND));
        } finally {
            chatPipelineMetrics.recordStage("room.get_by_id", startNanos);
        }
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

        afterCommitExecutor.execute(() -> {
            roomSessionRegistry.closeAllSessionsInRoom(roomId);
            roomLifecyclePublisher.publishRoomEnded(roomId, "OWNER_DELETED");
        });
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
        long startNanos = System.nanoTime();
        int updated = roomRepository.updateLastMessageIfNewer(roomId, timestamp, message, senderName);
        chatPipelineMetrics.recordStage("room.update_last_message", startNanos);
        if (updated > 0) {
            log.debug("[ROOM UPDATE] roomId={} lastMessageAt={} sender={}", roomId, timestamp, senderName);
        }
    }
}
