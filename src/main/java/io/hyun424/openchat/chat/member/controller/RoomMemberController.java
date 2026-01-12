package io.hyun424.openchat.chat.member.controller;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.global.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomMemberController {

    private final RoomMemberService roomMemberService;

    /** 방 입장 */
    @PostMapping("/{roomId}/join")
    public ApiResponse<Void> join(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        String userId = authentication.getPrincipal().toString();
        roomMemberService.join(roomId, userId);
        return ApiResponse.ok();
    }

    /** 방 퇴장 */
    @PostMapping("/{roomId}/leave")
    public ApiResponse<Void> leave(
            @PathVariable Long roomId,
            Authentication authentication
    ) {
        String userId = authentication.getPrincipal().toString();
        roomMemberService.leave(roomId, userId);
        return ApiResponse.ok();
    }
}
