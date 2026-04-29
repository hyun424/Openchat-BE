package io.hyun424.openchat.auth.controller;

import io.hyun424.openchat.auth.dto.NicknameRequest;
import io.hyun424.openchat.auth.dto.TokenResponse;
import io.hyun424.openchat.auth.entity.User;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.auth.service.UserService;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    private final JwtProvider jwtProvider;
    private final UserService userService;

    /**
     * 닉네임 중복 체크
     */
    @GetMapping("/check-nickname")
    public boolean checkNickname(@RequestParam String nickname) {
        return userService.isNicknameAvailable(nickname);
    }




    /**
     * 닉네임 설정 + 회원가입 완료
     * 임시 토큰을 정식 JWT로 교환
     */
    @PostMapping("/setup-nickname")
    public TokenResponse setupNickname(
            @RequestHeader("Authorization") String authHeader,
            @Valid @RequestBody NicknameRequest request
    ) {
        String tempToken = extractToken(authHeader);

        // 임시 토큰 검증
        if (!jwtProvider.validateToken(tempToken) || !jwtProvider.isTempToken(tempToken)) {
            throw new ApiException(ErrorCode.INVALID_TOKEN);
        }

        // 토큰에서 Google 정보 추출
        String googleId = jwtProvider.getUserId(tempToken);
        String email = jwtProvider.getEmailFromTempToken(tempToken);
        String picture = jwtProvider.getPictureFromTempToken(tempToken);

        // 유저 생성
        User user = userService.createUser(googleId, email, request.getNickname(), picture);

        // 정식 JWT 발급
        String token = jwtProvider.createToken(user.getId(), user.getNickname());

        return new TokenResponse(token, user.getNickname());
    }

    private String extractToken(String authHeader) {
        if (authHeader != null && authHeader.startsWith("Bearer ")) {
            return authHeader.substring(7);
        }
        throw new ApiException(ErrorCode.INVALID_TOKEN);
    }
}
