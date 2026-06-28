package io.hyun424.openchat.auth.oauth;

import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.util.StringUtils;

import java.util.Locale;
import java.util.Map;

public record OAuth2ProviderProfile(
        String provider,
        String providerUserId,
        String userId,
        String email,
        String name,
        String picture
) {
    public static OAuth2ProviderProfile from(String registrationId, OAuth2User user) {
        String provider = normalizeProvider(registrationId);
        return switch (provider) {
            case "google" -> google(user);
            case "kakao" -> kakao(user);
            case "naver" -> naver(user);
            default -> throw invalidProvider(provider);
        };
    }

    private static OAuth2ProviderProfile google(OAuth2User user) {
        String providerUserId = requiredString(user.getAttribute("sub"), "google.sub");
        return new OAuth2ProviderProfile(
                "google",
                providerUserId,
                providerUserId("google", providerUserId),
                stringOrSyntheticEmail(user.getAttribute("email"), "google", providerUserId),
                stringOrFallback(user.getAttribute("name"), "Google User"),
                stringOrNull(user.getAttribute("picture"))
        );
    }

    @SuppressWarnings("unchecked")
    private static OAuth2ProviderProfile kakao(OAuth2User user) {
        String providerUserId = requiredString(user.getAttribute("id"), "kakao.id");
        Map<String, Object> account = mapOrEmpty(user.getAttribute("kakao_account"));
        Map<String, Object> profile = mapOrEmpty(account.get("profile"));
        return new OAuth2ProviderProfile(
                "kakao",
                providerUserId,
                providerUserId("kakao", providerUserId),
                stringOrSyntheticEmail(account.get("email"), "kakao", providerUserId),
                stringOrFallback(profile.get("nickname"), "Kakao User"),
                stringOrNull(profile.get("profile_image_url"))
        );
    }

    private static OAuth2ProviderProfile naver(OAuth2User user) {
        Map<String, Object> response = mapOrEmpty(user.getAttribute("response"));
        String providerUserId = requiredString(response.get("id"), "naver.response.id");
        return new OAuth2ProviderProfile(
                "naver",
                providerUserId,
                providerUserId("naver", providerUserId),
                stringOrSyntheticEmail(response.get("email"), "naver", providerUserId),
                stringOrFallback(response.get("nickname"), "Naver User"),
                stringOrNull(response.get("profile_image"))
        );
    }

    private static String normalizeProvider(String registrationId) {
        if (!StringUtils.hasText(registrationId)) {
            throw invalidProvider("missing");
        }
        return registrationId.trim().toLowerCase(Locale.ROOT);
    }

    private static String providerUserId(String provider, String providerUserId) {
        return provider + ":" + providerUserId;
    }

    private static String requiredString(Object value, String field) {
        String normalized = stringOrNull(value);
        if (!StringUtils.hasText(normalized)) {
            throw new OAuth2AuthenticationException(new OAuth2Error(
                    "invalid_user_info",
                    "Missing OAuth2 user info field: " + field,
                    null
            ));
        }
        return normalized;
    }

    private static String stringOrFallback(Object value, String fallback) {
        String normalized = stringOrNull(value);
        return StringUtils.hasText(normalized) ? normalized : fallback;
    }

    private static String stringOrSyntheticEmail(Object value, String provider, String providerUserId) {
        String normalized = stringOrNull(value);
        return StringUtils.hasText(normalized)
                ? normalized
                : provider + ":" + providerUserId + "@oauth.openchat.local";
    }

    private static String stringOrNull(Object value) {
        return value == null ? null : String.valueOf(value).trim();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> mapOrEmpty(Object value) {
        return value instanceof Map<?, ?> map ? (Map<String, Object>) map : Map.of();
    }

    private static OAuth2AuthenticationException invalidProvider(String provider) {
        return new OAuth2AuthenticationException(new OAuth2Error(
                "unsupported_oauth_provider",
                "Unsupported OAuth2 provider: " + provider,
                null
        ));
    }
}
