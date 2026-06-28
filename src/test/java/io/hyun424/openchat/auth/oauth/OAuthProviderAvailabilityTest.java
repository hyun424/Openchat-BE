package io.hyun424.openchat.auth.oauth;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OAuthProviderAvailabilityTest {

    @Test
    void providerIsEnabledOnlyWhenClientIdAndSecretExist() {
        assertTrue(OAuthProviderAvailability.enabled("id", "secret"));
        assertFalse(OAuthProviderAvailability.enabled("id", ""));
        assertFalse(OAuthProviderAvailability.enabled("", "secret"));
        assertFalse(OAuthProviderAvailability.enabled(null, "secret"));
    }
}
