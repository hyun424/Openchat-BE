package io.hyun424.openchat.chat.room.controller;

import io.hyun424.openchat.chat.room.domain.Room;
import io.hyun424.openchat.chat.room.dto.RoomCreateRequest;
import io.hyun424.openchat.chat.room.dto.RoomResponse;
import io.hyun424.openchat.chat.room.service.RoomService;
import io.hyun424.openchat.chat.member.service.RoomMemberService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

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
            @RequestBody RoomCreateRequest request
    ) {
        System.out.println("AUTH = " + authentication);

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName(); // ✅ JWT subject

        Room room = roomService.createRoom(userId, request.getName());

        return ResponseEntity.ok(RoomResponse.from(room));
    }


    /**
     * 방 입장
     * - 이미 입장한 경우에도 에러 ❌
     * - 상태 변경 API이므로 JOIN은 여기서만
     */
    @PostMapping("/{roomId}/enter")
    public ResponseEntity<Void> enterRoom(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        if (authentication == null || !authentication.isAuthenticated()) {
            throw new IllegalStateException("Unauthenticated request");
        }

        String userId = authentication.getName();

        roomMemberService.joinIfNotExists(roomId, userId);

        return ResponseEntity.ok().build();
    }



    /**
     * 방 목록 조회
     * - JOIN ❌
     * - 상태 변경 없음 (순수 조회)
     */
    @GetMapping
    public ResponseEntity<List<RoomResponse>> getRooms() {
        return ResponseEntity.ok(
                roomService.getRooms()
                        .stream()
                        .map(RoomResponse::from)
                        .toList()
        );
    }
}
