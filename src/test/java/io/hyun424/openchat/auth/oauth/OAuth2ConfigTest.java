package io.hyun424.openchat.auth.oauth;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertNotNull;

class OAuth2ConfigTest {

    @Test
    void registersConfiguredGoogleKakaoAndNaverClients() {
        OAuth2Config config = new OAuth2Config();
        ReflectionTestUtils.setField(config, "googleClientId", "google-id");
        ReflectionTestUtils.setField(config, "googleClientSecret", "google-secret");
        ReflectionTestUtils.setField(config, "kakaoClientId", "kakao-id");
        ReflectionTestUtils.setField(config, "kakaoClientSecret", "kakao-secret");
        ReflectionTestUtils.setField(config, "naverClientId", "naver-id");
        ReflectionTestUtils.setField(config, "naverClientSecret", "naver-secret");

        ClientRegistrationRepository repository = config.clientRegistrationRepository();

        assertNotNull(((InMemoryClientRegistrationRepository) repository).findByRegistrationId("google"));
        assertNotNull(((InMemoryClientRegistrationRepository) repository).findByRegistrationId("kakao"));
        assertNotNull(((InMemoryClientRegistrationRepository) repository).findByRegistrationId("naver"));
    }
}
