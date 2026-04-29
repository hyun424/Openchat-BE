package io.hyun424.openchat.auth.controller;

import io.hyun424.openchat.auth.dto.LoginRequest;
import io.hyun424.openchat.auth.dto.LoginResponse;
import io.hyun424.openchat.auth.jwt.JwtProvider;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.*;

/**
 * 개발용 간편 로그인 API
 * - dev, local 프로파일에서만 활성화
 * - 운영(prod) 환경에서는 자동 비활성화
 */
@Slf4j
@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
@Profile({"dev", "local", "loadtest"})
public class DevAuthController {

    private final JwtProvider jwtProvider;

    /**
     * 개발용 간편 로그인
     * 운영 환경에서는 이 엔드포인트가 존재하지 않음
     */
    @PostMapping("/login")
    public LoginResponse login(@Valid @RequestBody LoginRequest request) {
        log.warn("[DEV_LOGIN] userId={} - This endpoint should not exist in production!",
                request.getUserId());

        String token = jwtProvider.createToken(
                request.getUserId(),
                request.getNickname()
        );
        return new LoginResponse(token);
    }
}
