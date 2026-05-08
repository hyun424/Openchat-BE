package io.hyun424.openchat.chat.room.controller;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.member.service.RoomMemberService.JoinResult;
import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.dto.MyRoomResponse;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.dto.RoomListResponse;
import io.hyun424.openchat.chat.room.dto.RoomMapResponse;
import io.hyun424.openchat.chat.room.dto.RoomResponse;
import io.hyun424.openchat.chat.room.partition.dto.RoomPartitionRoute;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRouteUnavailableException;
import io.hyun424.openchat.chat.room.partition.service.RoomPartitionRoutingService;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.math.BigDecimal;
import java.util.List;

@Validated
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomController {

    private final RoomService roomService;
    private final RoomMemberService roomMemberService;
    private final RoomPartitionRoutingService roomPartitionRoutingService;

    private static final int DEFAULT_PAGE = 0;
    private static final int MAX_PAGE_SIZE = 50;

    /**
     * 방 생성은 인증된 사용자만 가능하다.
     * 인증 확인을 controller 경계에서 끝내면 service는 도메인 규칙에 집중할 수 있다.
     */
    @PostMapping
    public ResponseEntity<RoomResponse> createRoom(
            Authentication authentication,
            @Valid @RequestBody RoomCreateRequest request
    ) {
        String userId = authenticatedUserId(authentication);
        Room room = roomService.createRoom(userId, request);

        return ResponseEntity.ok(RoomResponse.from(room));
    }


    /**
     * 방 삭제 (방장만 가능, Soft Delete)
     */
    @DeleteMapping("/{roomId}")
    public ResponseEntity<Void> deleteRoom(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authenticatedUserId(authentication);
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
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authenticatedUserId(authentication);
        JoinResult result = roomMemberService.joinIfNotExists(roomId, userId);

        return ResponseEntity.ok(new JoinResponse(result.status().name(), result.requiresApproval()));
    }

    public record JoinResponse(String status, boolean requiresApproval) {}



    /**
     * 방 목록 조회 (현재 인원수 포함, 페이지네이션)
     */
    @GetMapping
    public ResponseEntity<Page<RoomListResponse>> getRooms(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        PageRequestOption pageRequest = normalizePageRequest(page, size);
        return ResponseEntity.ok(roomService.getRoomsWithMemberCount(pageRequest.page(), pageRequest.size()));
    }

    /**
     * 방 상세 조회 (현재 인원수 포함)
     */
    @GetMapping("/{roomId}")
    public ResponseEntity<RoomDetailResponse> getRoom(@PathVariable @Positive Long roomId) {
        Room room = roomService.getRoomOrThrow(roomId);
        int currentMembers = roomMemberService.getApprovedMemberCount(roomId);
        return ResponseEntity.ok(RoomDetailResponse.from(room, currentMembers));
    }

    @GetMapping("/{roomId}/ws-route")
    public ResponseEntity<?> getWebSocketRoute(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authenticatedUserId(authentication);
        roomService.getActiveRoomOrThrow(roomId);
        roomMemberService.getJoinedAtOrThrow(roomId, userId);
        try {
            return ResponseEntity.ok(roomPartitionRoutingService.route(roomId, userId));
        } catch (RoomPartitionRouteUnavailableException e) {
            long retryAfterSeconds = Math.max(1, (long) Math.ceil(e.retryAfterMs() / 1000.0));
            return ResponseEntity.status(503)
                    .header(HttpHeaders.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .body(new WebSocketRouteUnavailableResponse(e.reason(), e.retryAfterMs()));
        }
    }

    public record WebSocketRouteUnavailableResponse(
            String reason,
            long retryAfterMs
    ) {
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
     * 지도용 방 목록 (위치 정보가 있는 방만, bounding box 필터)
     */
    @GetMapping("/map")
    public ResponseEntity<List<RoomMapResponse>> getRoomsForMap(
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal swLat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal swLng,
            @RequestParam @DecimalMin("-90.0") @DecimalMax("90.0") BigDecimal neLat,
            @RequestParam @DecimalMin("-180.0") @DecimalMax("180.0") BigDecimal neLng
    ) {
        return ResponseEntity.ok(roomService.getRoomsForMap(swLat, swLng, neLat, neLng));
    }

    /**
     * My Chats: 사용자가 참여 중인 채팅방 목록
     * - 최근 메시지가 있는 방이 상단에 표시
     * - lastMessageAt 기준 내림차순 정렬
     */
    @GetMapping("/my")
    public ResponseEntity<List<MyRoomResponse>> getMyRooms(Authentication authentication) {
        String userId = authenticatedUserId(authentication);
        return ResponseEntity.ok(roomService.getMyRooms(userId));
    }

    private String authenticatedUserId(Authentication authentication) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return authentication.getName();
    }

    private PageRequestOption normalizePageRequest(int page, int size) {
        int normalizedPage = Math.max(DEFAULT_PAGE, page);
        int normalizedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        return new PageRequestOption(normalizedPage, normalizedSize);
    }

    private record PageRequestOption(int page, int size) {}
}
