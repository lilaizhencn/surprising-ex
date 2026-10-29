package com.surprising.gateway.provider.auth.client;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.config.GoogleLoginProperties;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.*;

class GoogleIdentityFailureTest {
    GoogleLoginProperties properties() {
        var properties = new GoogleLoginProperties();
        properties.setWebClientId("web.apps.googleusercontent.com");
        return properties;
    }
    @Test void invalidCredentialsAreRejectedWithoutReflectingTheirDetails() {
        var client = new GoogleIdentityClient(properties(), token -> { throw new BadJwtException("sensitive-token-detail"); });
        assertThatThrownBy(() -> client.verify("credential", "nonce", "WEB"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("Google authorization is invalid or expired");
    }
    @Test void keyServiceFailureIsRetryableAndDoesNotMasqueradeAsBadCredentials() {
        var client = new GoogleIdentityClient(properties(), token -> { throw new JwtException("upstream-error-details"); });
        assertThatThrownBy(() -> client.verify("credential", "nonce", "WEB"))
                .isInstanceOf(GoogleIdentityClient.Unavailable.class).hasMessage("Google sign-in is temporarily unavailable");
    }
}
