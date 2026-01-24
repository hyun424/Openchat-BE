package io.hyun424.openchat.auth.controller;

import io.hyun424.openchat.auth.dto.NicknameRequest;
import io.hyun424.openchat.auth.entity.User;
import io.hyun424.openchat.auth.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 내 정보 조회
     */
    @GetMapping("/me")
    public UserResponse getMe(Authentication auth) {
        User user = userService.getUser(auth.getName());
        return new UserResponse(user);
    }

    /**
     * 닉네임 변경
     */
    @PatchMapping("/me/nickname")
    public void updateNickname(Authentication auth,
                               @Valid @RequestBody NicknameRequest request) {
        userService.updateNickname(auth.getName(), request.getNickname());
    }

    public record UserResponse(
            String id,
            String email,
            String nickname,
            String profileImage
    ) {
        public UserResponse(User user) {
            this(user.getId(), user.getEmail(), user.getNickname(), user.getProfileImage());
        }
    }
}
