package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class UserSecurityServiceTest {

    private final AuthPersistenceService persistence = mock(AuthPersistenceService.class);
    private final GatewayUserSecuritySceneRepository sceneRepository = mock(GatewayUserSecuritySceneRepository.class);
    private final TotpService totpService = mock(TotpService.class);
    private final UserSecurityService service = new UserSecurityService(
            persistence, sceneRepository, totpService);

    @Test
    void enrollsAndConfirmsUserTotp() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        when(persistence.user(42L)).thenReturn(Optional.of(user(now)));
        when(totpService.newSecret()).thenReturn("JBSWY3DPEHPK3PXP");
        when(totpService.encryptSecret("JBSWY3DPEHPK3PXP")).thenReturn("ciphertext");
        when(persistence.mfaCredential(42L)).thenReturn(Optional.of(new GatewayUserMfaRepository.MfaCredential(
                42L, "ciphertext", false, null, now, now)));
        when(totpService.decryptSecret("ciphertext")).thenReturn("JBSWY3DPEHPK3PXP");
        when(totpService.verify(eq("JBSWY3DPEHPK3PXP"), eq("123456"), any())).thenReturn(true);

        var enrollment = service.enrollMfa(42L);
        var status = service.confirmMfa(42L, "123456");

        assertThat(enrollment.secret()).isEqualTo("JBSWY3DPEHPK3PXP");
        assertThat(status.enabled()).isTrue();
        verify(persistence).upsertMfaSecret(eq(42L), eq("ciphertext"), any());
        verify(persistence).enableMfa(eq(42L), any());
    }

    @Test
    void sensitiveScenesDefaultToEnabledExceptTransfers() {
        when(sceneRepository.find(42L)).thenReturn(List.of());

        var scenes = service.scenes(42L);

        assertThat(scenes).filteredOn(scene -> scene.sceneCode().equals("WITHDRAWAL"))
                .singleElement().extracting(UserSecurityService.Scene::enabled).isEqualTo(true);
        assertThat(scenes).filteredOn(scene -> scene.sceneCode().equals("TRANSFER"))
                .singleElement().extracting(UserSecurityService.Scene::enabled).isEqualTo(false);
    }

    @Test
    void disablingSceneRequiresEnabledTotp() {
        Instant now = Instant.parse("2026-08-04T00:00:00Z");
        when(sceneRepository.findOne(42L, "WITHDRAWAL"))
                .thenReturn(Optional.of(new GatewayUserSecuritySceneRepository.SceneRecord(42L, "WITHDRAWAL", true, now, now)));
        when(persistence.mfaCredential(42L)).thenReturn(Optional.of(new GatewayUserMfaRepository.MfaCredential(
                42L, "ciphertext", true, now, now, now)));
        when(totpService.decryptSecret("ciphertext")).thenReturn("SECRET");
        when(totpService.verify(eq("SECRET"), eq("000000"), any())).thenReturn(false);

        assertThatThrownBy(() -> service.updateScene(42L, "WITHDRAWAL", false, "000000"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid totp code");
    }

    private AuthenticatedUser user(Instant now) {
        return new AuthenticatedUser(42L, null, "user@example.com", "NORMAL", List.of("USER"), now);
    }
}
