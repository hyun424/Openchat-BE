package io.hyun424.openchat.auth.resolver;


import io.hyun424.openchat.auth.jwt.JwtProvider;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class AuthUserResolver {

    private final JwtProvider jwtProvider;

    public String extractUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization 헤더가 올바르지 않습니다.");
        }
        String token = authorization.substring(7);
        return jwtProvider.getUserId(token);
    }
}