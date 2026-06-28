package io.hyun424.openchat.auth.resolver;


import io.hyun424.openchat.auth.jwt.JwtProvider;
import io.hyun424.openchat.global.exception.ApiException;
import io.hyun424.openchat.global.exception.ErrorCode;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.Locale;

@Component
@RequiredArgsConstructor
public class AuthUserResolver {

    public static final String ANONYMOUS_ID_HEADER = "X-OpenChat-Anonymous-Id";
    public static final String ANONYMOUS_NICKNAME_HEADER = "X-OpenChat-Anonymous-Nickname";
    public static final String ANONYMOUS_USER_PREFIX = "anon:";

    private static final int MAX_ANONYMOUS_ID_LENGTH = 58;
    private static final int MAX_NICKNAME_LENGTH = 40;

    private final JwtProvider jwtProvider;

    public String extractUserId(String authorization) {
        if (authorization == null || !authorization.startsWith("Bearer ")) {
            throw new IllegalArgumentException("Authorization 헤더가 올바르지 않습니다.");
        }
        String token = authorization.substring(7);
        return jwtProvider.getUserId(token);
    }

    public String resolveUserId(Authentication authentication, String authorization, String anonymousId) {
        if (authentication != null && authentication.isAuthenticated() && authentication.getName() != null) {
            return authentication.getName();
        }
        if (StringUtils.hasText(authorization)) {
            return extractUserId(authorization);
        }
        return anonymousUserId(anonymousId);
    }

    public String anonymousUserId(String anonymousId) {
        String normalized = normalizeAnonymousId(anonymousId);
        if (!StringUtils.hasText(normalized)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        return ANONYMOUS_USER_PREFIX + normalized;
    }

    public String anonymousNickname(String nickname, String anonymousId) {
        String normalized = normalizeNickname(nickname);
        if (StringUtils.hasText(normalized)) {
            return normalized;
        }
        String normalizedId = normalizeAnonymousId(anonymousId);
        if (!StringUtils.hasText(normalizedId)) {
            throw new ApiException(ErrorCode.UNAUTHORIZED);
        }
        String suffix = normalizedId.length() <= 6
                ? normalizedId
                : normalizedId.substring(normalizedId.length() - 6);
        return "Guest-" + suffix;
    }

    private String normalizeAnonymousId(String anonymousId) {
        if (!StringUtils.hasText(anonymousId)) {
            return null;
        }
        String normalized = anonymousId.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith(ANONYMOUS_USER_PREFIX)) {
            normalized = normalized.substring(ANONYMOUS_USER_PREFIX.length());
        }
        normalized = normalized.replaceAll("[^a-z0-9_-]", "");
        if (normalized.isBlank()) {
            return null;
        }
        if (normalized.length() > MAX_ANONYMOUS_ID_LENGTH) {
            normalized = normalized.substring(0, MAX_ANONYMOUS_ID_LENGTH);
        }
        return normalized;
    }

    private String normalizeNickname(String nickname) {
        if (!StringUtils.hasText(nickname)) {
            return null;
        }
        String normalized = nickname.trim().replaceAll("[\\r\\n\\t]", " ");
        if (normalized.isBlank()) {
            return null;
        }
        return normalized.length() > MAX_NICKNAME_LENGTH
                ? normalized.substring(0, MAX_NICKNAME_LENGTH)
                : normalized;
    }
}
