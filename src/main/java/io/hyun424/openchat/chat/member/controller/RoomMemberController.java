package io.hyun424.openchat.chat.member.controller;

import io.hyun424.openchat.chat.member.service.RoomMemberService;
import io.hyun424.openchat.chat.member.service.RoomMemberService.JoinResult;
import io.hyun424.openchat.global.response.ApiResponse;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@Validated
@RestController
@RequestMapping("/api/rooms")
@RequiredArgsConstructor
public class RoomMemberController {

    private final RoomMemberService roomMemberService;

    /**
     * 방 입장
     * @return { status: "APPROVED" | "PENDING", requiresApproval: boolean }
     */
    @PostMapping("/{roomId}/join")
    public ApiResponse<JoinResponse> join(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        JoinResult result = roomMemberService.join(roomId, userId);
        return ApiResponse.ok(JoinResponse.from(result));
    }

    /** 방 퇴장 */
    @PostMapping("/{roomId}/leave")
    public ApiResponse<Void> leave(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        roomMemberService.leave(roomId, userId);
        return ApiResponse.ok();
    }

    /** 멤버 승인 (방장만) */
    @PostMapping("/{roomId}/members/{userId}/approve")
    public ApiResponse<Void> approveMember(
            @PathVariable @Positive Long roomId,
            @PathVariable @NotBlank String userId,
            Authentication authentication
    ) {
        String requesterId = authentication.getName();
        roomMemberService.approveMember(roomId, userId, requesterId);
        return ApiResponse.ok();
    }

    /** 멤버 거절 (방장만) */
    @PostMapping("/{roomId}/members/{userId}/reject")
    public ApiResponse<Void> rejectMember(
            @PathVariable @Positive Long roomId,
            @PathVariable @NotBlank String userId,
            Authentication authentication
    ) {
        String requesterId = authentication.getName();
        roomMemberService.rejectMember(roomId, userId, requesterId);
        return ApiResponse.ok();
    }

    /** 대기 중인 멤버 목록 (방장만) */
    @GetMapping("/{roomId}/pending-members")
    public ApiResponse<List<PendingMemberResponse>> getPendingMembers(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String requesterId = authentication.getName();
        var members = roomMemberService.getPendingMembers(roomId, requesterId);
        List<PendingMemberResponse> response = members.stream()
                .map(m -> new PendingMemberResponse(m.userId(), m.nickname(), m.joinedAt().toString()))
                .toList();
        return ApiResponse.ok(response);
    }

    /** 현재 인원수 */
    @GetMapping("/{roomId}/member-count")
    public ApiResponse<Integer> getMemberCount(@PathVariable @Positive Long roomId) {
        int count = roomMemberService.getApprovedMemberCount(roomId);
        return ApiResponse.ok(count);
    }

    /** 내 멤버십 상태 확인 */
    @GetMapping("/{roomId}/membership")
    public ApiResponse<MembershipResponse> getMembership(
            @PathVariable @Positive Long roomId,
            Authentication authentication
    ) {
        String userId = authentication.getName();
        var status = roomMemberService.getMembershipStatus(roomId, userId);
        return ApiResponse.ok(MembershipResponse.from(status));
    }

    public record MembershipResponse(boolean isMember, String status) {
        public static MembershipResponse from(RoomMemberService.MembershipStatus s) {
            return new MembershipResponse(
                    s.isMember(),
                    s.status() != null ? s.status().name() : "NONE"
            );
        }
    }

    // === DTOs ===

    public record JoinResponse(String status, boolean requiresApproval) {
        public static JoinResponse from(JoinResult result) {
            return new JoinResponse(result.status().name(), result.requiresApproval());
        }
    }

    public record PendingMemberResponse(String userId, String nickname, String joinedAt) {}
}
