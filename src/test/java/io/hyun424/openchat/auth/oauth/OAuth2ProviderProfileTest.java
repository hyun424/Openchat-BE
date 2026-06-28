package io.hyun424.openchat.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.user.DefaultOAuth2User;
import org.springframework.security.oauth2.core.user.OAuth2User;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class OAuth2ProviderProfileTest {

    @Test
    void extractsGoogleProfile() {
        OAuth2User user = oauthUser("sub", Map.of(
                "sub", "google-123",
                "email", "google@example.com",
                "name", "Google User",
                "picture", "https://example.com/google.png"
        ));

        OAuth2ProviderProfile profile = OAuth2ProviderProfile.from("google", user);

        assertEquals("google:google-123", profile.userId());
        assertEquals("google@example.com", profile.email());
        assertEquals("Google User", profile.name());
        assertEquals("https://example.com/google.png", profile.picture());
        assertEquals("google", profile.provider());
    }

    @Test
    void extractsKakaoProfile() {
        OAuth2User user = oauthUser("id", Map.of(
                "id", 123456789L,
                "kakao_account", Map.of(
                        "email", "kakao@example.com",
                        "profile", Map.of(
                                "nickname", "Kakao User",
                                "profile_image_url", "https://example.com/kakao.png"
                        )
                )
        ));

        OAuth2ProviderProfile profile = OAuth2ProviderProfile.from("kakao", user);

        assertEquals("kakao:123456789", profile.userId());
        assertEquals("kakao@example.com", profile.email());
        assertEquals("Kakao User", profile.name());
        assertEquals("https://example.com/kakao.png", profile.picture());
        assertEquals("kakao", profile.provider());
    }

    @Test
    void extractsNaverProfile() {
        OAuth2User user = oauthUser("response", Map.of(
                "response", Map.of(
                        "id", "naver-123",
                        "email", "naver@example.com",
                        "nickname", "Naver User",
                        "profile_image", "https://example.com/naver.png"
                )
        ));

        OAuth2ProviderProfile profile = OAuth2ProviderProfile.from("naver", user);

        assertEquals("naver:naver-123", profile.userId());
        assertEquals("naver@example.com", profile.email());
        assertEquals("Naver User", profile.name());
        assertEquals("https://example.com/naver.png", profile.picture());
        assertEquals("naver", profile.provider());
    }

    private OAuth2User oauthUser(String nameAttributeKey, Map<String, Object> attributes) {
        return new DefaultOAuth2User(List.of(), attributes, nameAttributeKey);
    }
}
