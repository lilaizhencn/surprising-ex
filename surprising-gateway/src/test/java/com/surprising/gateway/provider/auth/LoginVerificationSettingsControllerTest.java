package com.surprising.gateway.provider.auth;

import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class LoginVerificationSettingsControllerTest {
    final AuthService auth = mock(AuthService.class);
    final AuthPersistenceService users = mock(AuthPersistenceService.class);
    final LoginVerificationRepository challenges = mock(LoginVerificationRepository.class);
    final GatewayProperties properties = new GatewayProperties();
    final AuthModels.JwtPrincipal principal = new AuthModels.JwtPrincipal(
            1, null, "NORMAL", List.of("USER"), Instant.now().plusSeconds(600), 42);
    MockMvc mvc;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setup() {
        when(auth.authenticateBearer("Bearer synthetic")).thenReturn(principal);
        when(users.credential(1)).thenReturn(Optional.of(new GatewayUserRepository.UserCredential(
                1, null, null, null, null, "NORMAL", Instant.now())));
        var service = new LoginVerificationService(challenges, users, properties, new TotpService(properties),
                mock(EmailMessageSender.class), mock(ObjectProvider.class), mock(PasswordHasher.class));
        mvc = MockMvcBuilders.standaloneSetup(new LoginVerificationSettingsController(auth, service)).build();
    }

    @Test
    void passwordlessAuthenticationContextIsPrivateAndReportsMissingRecentProof() throws Exception {
        mvc.perform(get("/api/v1/security/login-verification/authentication")
                        .header("Authorization", "Bearer synthetic"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.passwordRequired").value(false))
                .andExpect(jsonPath("$.reauthenticationRequired").value(true));
        verify(auth).primaryAuthenticatedAt(principal);
    }

    @Test
    void requestBodyCannotSupplyOrRenewPrimaryAuthentication() throws Exception {
        mvc.perform(post("/api/v1/security/login-verification/EMAIL/verify-password")
                        .header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currentPassword\":null,\"authenticatedAt\":\"2099-01-01T00:00:00Z\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("LOGIN_RECENT_AUTHENTICATION_REQUIRED"));
        verifyNoInteractions(challenges);
    }

    @Test
    void passwordlessRequestAcceptsOnlyTheServerSessionProofAndDoesNotIssueCodesInTheFirstStep() throws Exception {
        when(auth.primaryAuthenticatedAt(principal)).thenReturn(Instant.now().minusSeconds(10));
        mvc.perform(post("/api/v1/security/login-verification/EMAIL/verify-password")
                        .header("Authorization", "Bearer synthetic").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isOk());
        verifyNoInteractions(challenges);
    }
}
