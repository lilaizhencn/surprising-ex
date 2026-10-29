package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.client.GoogleIdentityClient;
import com.surprising.gateway.provider.auth.model.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;

class GoogleLoginServiceTest {
    GoogleLoginRepository identities = mock(GoogleLoginRepository.class);
    GoogleIdentityClient google = mock(GoogleIdentityClient.class);
    GatewayUserRepository rows = mock(GatewayUserRepository.class);
    AuthPersistenceService users = mock(AuthPersistenceService.class);
    AuthService auth = mock(AuthService.class);
    com.surprising.affiliate.service.AffiliateService affiliates = mock(com.surprising.affiliate.service.AffiliateService.class);
    GoogleLoginService service = new GoogleLoginService(identities, google, rows, users, auth, affiliates);
    AuthClientMetadata device = new AuthClientMetadata("test", "127.0.0.1", "device");
    String nonce = "n".repeat(43);
    @BeforeEach void setup() {
        when(google.verify(anyString(), any(), anyString())).thenReturn(new GoogleIdentityClient.Profile("google-123", "小李 🚀"));
        when(identities.consumeChallenge(anyString(), eq("device"), any())).thenReturn(true);
    }
    @Test void firstLoginCreatesPasswordlessAccountWithoutEmailLinking() {
        when(identities.lockIdentity("google-123")).thenReturn(Optional.empty());
        when(users.createUser(isNull(), isNull(), isNull(), any())).thenReturn(new AuthenticatedUser(7L, null, null, "NORMAL", List.of("USER"), Instant.now()));
        service.login("token", nonce, "WEB", null, device);
        verify(users).createUser(isNull(), isNull(), isNull(), any());
        verify(identities).bind(eq("google-123"), eq(7L), any());
        verify(auth).loginGoogle(7L, device);
    }
    @Test void returningLoginPreservesMfaResultAndDoesNotCreateAnotherAccount() {
        when(identities.lockIdentity("google-123")).thenReturn(Optional.of(7L));
        var challenge = mock(LoginVerificationModels.ChallengeResponse.class);
        when(auth.loginGoogle(7L, device)).thenReturn(challenge);
        assertThat(service.login("token", nonce, "WEB", null, device)).isSameAs(challenge);
        verifyNoInteractions(users);
    }
    @Test void replayOrAnotherDeviceCannotReachAccount() {
        when(identities.consumeChallenge(anyString(), eq("device"), any())).thenReturn(false);
        assertThatThrownBy(() -> service.login("token", nonce, "WEB", null, device)).isInstanceOf(IllegalArgumentException.class);
        verify(identities, never()).lockIdentity(any()); verifyNoInteractions(auth, users, rows);
    }
    @Test void invalidCredentialsNeverConsumeNonceOrCreateUser() {
        when(google.verify(anyString(), any(), anyString())).thenThrow(new IllegalArgumentException("invalid"));
        assertThatThrownBy(() -> service.login("token", nonce, "WEB", null, device)).isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(identities, auth, users, rows);
    }
}
