package io.hyun424.openchat.auth.oauth;

import org.springframework.util.StringUtils;

public final class OAuthProviderAvailability {
    private OAuthProviderAvailability() {
    }

    public static boolean enabled(String clientId, String clientSecret) {
        return StringUtils.hasText(clientId) && StringUtils.hasText(clientSecret);
    }
}
