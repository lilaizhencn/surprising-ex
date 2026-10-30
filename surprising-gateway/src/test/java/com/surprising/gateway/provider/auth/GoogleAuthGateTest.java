package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.surprising.gateway.provider.auth.model.*;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.auth.port.CurrentAuthClient;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;

class GoogleAuthGateTest {
    AuthPersistenceService users = mock(AuthPersistenceService.class);
    JwtTokenService tokens = mock(JwtTokenService.class);
    LoginVerificationService verification = mock(LoginVerificationService.class);
    GatewayUserAccessBlockRepository blocks = mock(GatewayUserAccessBlockRepository.class);
    AuthService auth = new AuthService(new GatewayProperties(), users, mock(PasswordHasher.class), tokens,
            mock(EmailVerificationService.class), verification, blocks, mock(CurrentAuthClient.class), mock(com.surprising.affiliate.service.AffiliateService.class), org.mockito.Mockito.mock(com.surprising.gateway.provider.legal.service.LegalConsentService.class));
    AuthClientMetadata request = new AuthClientMetadata("qa", "127.0.0.1", "device", java.util.List.of(), false);
    @Test void frozenGoogleUserCannotReachTokenIssuance() {
        when(users.user(7L)).thenReturn(Optional.of(new AuthenticatedUser(7, null, null, "FROZEN", List.of("USER"), Instant.now())));
        assertThatThrownBy(() -> auth.loginGoogle(7, request)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(tokens, verification);
    }
    @Test void passwordlessGoogleLoginStillRequiresEnrolledFactors() {
        when(users.user(7L)).thenReturn(Optional.of(new AuthenticatedUser(7, null, null, "NORMAL", List.of("USER"), Instant.now())));
        when(users.credential(7L)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(7, null, null, null, null, "NORMAL", Instant.now())));
        var challenge = new LoginVerificationModels.ChallengeResponse(true, "challenge", Instant.now().plusSeconds(300), List.of(new LoginVerificationModels.Method("TOTP", null)), false);
        when(verification.begin(eq(7L), isNull(), any())).thenReturn(challenge);
        assertThat(auth.loginGoogle(7, request)).isSameAs(challenge);
        verifyNoInteractions(tokens);
    }
}
