package io.hyun424.openchat.chat.room.controller;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.member.service.RoomMemberService.JoinResult;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.dto.MyRoomResponse;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.dto.RoomListResponse;
import io.hyun424.openchat.chat.room.dto.RoomMapResponse;
import io.hyun424.openchat.chat.room.dto.RoomResponse;
import io.hyun424.openchat.chat.room.service.RoomService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;

    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            Authentication authentication,
            @Valid @RequestBody RoomCreateRequest request
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName();
        Room room = roomService.createRoom(userId, request);

        return ResponseEntity.ok(RoomResponse.from(room));
    }


    /**
     * 방 삭제 (방장만 가능, Soft Delete)
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteRoom(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName();
        roomService.deleteRoom(roomId, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * 방 입장
     * - 이미 입장한 경우에도 에러 ❌
     * - 인원 초과 시 에러
     * - 승인 필요한 방은 PENDING 상태로 반환
     */
    @PostMapping("/{roomId}/enter")
    public ResponseEntity<JoinResponse> enterRoom(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName();
        JoinResult result = roomMemberService.joinIfNotExists(roomId, userId);

        return ResponseEntity.ok(new JoinResponse(result.status().name(), result.requiresApproval()));
    }

    public record JoinResponse(String status, boolean requiresApproval) {}



    /**
     * 방 목록 조회 (현재 인원수 포함)
     */
    @GetMapping
    public ResponseEntity<List<RoomListResponse>> getRooms() {
        return ResponseEntity.ok(roomService.getRoomsWithMemberCount());
    }

    /**
     * 방 상세 조회 (현재 인원수 포함)
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<RoomDetailResponse> getRoom(@PathVariable Long roomId) {
        Room room = roomService.getRoomOrThrow(roomId);
        int currentMembers = roomMemberService.getApprovedMemberCount(roomId);
        return ResponseEntity.ok(RoomDetailResponse.from(room, currentMembers));
    }

    public record RoomDetailResponse(
            Long id,
            String name,
            String ownerId,
            Integer maxMembers,
            Boolean requiresApproval,
            String description,
            String rules,
            String imageUrl,
            String category,
            String meetingDate,
            String meetingTime,
            BigDecimal lat,
            BigDecimal lng,
            String locationName,
            int currentMembers
    ) {
        public static RoomDetailResponse from(Room room, int currentMembers) {
            return new RoomDetailResponse(
                    room.getId(),
                    room.getName(),
                    room.getOwnerId(),
                    room.getMaxMembers(),
                    room.getRequiresApproval(),
                    room.getDescription(),
                    room.getRules(),
                    room.getImageUrl(),
                    room.getCategory(),
                    room.getMeetingDate() != null ? room.getMeetingDate().toString() : null,
                    room.getMeetingTime() != null ? room.getMeetingTime().toString() : null,
                    room.getLat(),
                    room.getLng(),
                    room.getLocationName(),
                    currentMembers
            );
        }
    }

    /**
     * 지도용 방 목록 (위치 정보가 있는 방만)
     */
    @GetMapping("/map")
    public ResponseEntity<List<RoomMapResponse>> getRoomsForMap() {
        return ResponseEntity.ok(roomService.getRoomsForMap());
    }

    /**
     * My Chats: 사용자가 참여 중인 채팅방 목록
     * - 최근 메시지가 있는 방이 상단에 표시
     * - lastMessageAt 기준 내림차순 정렬
     */
    @GetMapping("/my")
    public ResponseEntity<List<MyRoomResponse>> getMyRooms(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName();
        return ResponseEntity.ok(roomService.getMyRooms(userId));
    }
}
