package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.controller.UserCardController;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.service.AuthPersistenceService;
import com.surprising.gateway.provider.auth.service.AuthService;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

class UserCardControllerTest {
    AuthService auth = mock(AuthService.class);
    AuthPersistenceService users = mock(AuthPersistenceService.class);
    UserCardController controller = new UserCardController(auth, users);

    @Test
    void scanningReturnsOnlyServerResolvedPublicIdentity() {
        when(users.user(42)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                42, null, "private@example.test", "NORMAL", List.of("USER"), Instant.now(), "小李")));
        assertThat(controller.scan("Bearer access", new UserCardController.ScanRequest("surprising://user/42")))
                .isEqualTo(new UserCardController.PublicProfile(42, "小李"));
        verify(auth).authenticateBearer("Bearer access");
    }

    @Test
    void rejectsForeignUrlsMalformedIdsAndLongOverflow() {
        for (String code : List.of("https://attacker.example", "surprising://user/-1", "surprising://user/0",
                "surprising://user/42?email=secret", "surprising://user/9999999999999999999")) {
            assertThatThrownBy(() -> UserCardController.parseUserId(code)).isInstanceOf(ResponseStatusException.class);
        }
        verifyNoInteractions(users);
    }

    @Test
    void missingAndFrozenProfilesAreNotDisclosed() {
        when(users.user(42)).thenReturn(Optional.of(new AuthModels.AuthenticatedUser(
                42, null, null, "FROZEN", List.of("USER"), Instant.now(), "hidden")));
        assertThatThrownBy(() -> controller.scan("Bearer access", new UserCardController.ScanRequest("surprising://user/42")))
                .isInstanceOf(ResponseStatusException.class).extracting("statusCode.value").isEqualTo(404);
        assertThatThrownBy(() -> controller.scan("Bearer access", new UserCardController.ScanRequest("surprising://user/43")))
                .isInstanceOf(ResponseStatusException.class);
    }

    @Test
    void invalidSessionCannotLookUpAProfile() {
        when(auth.authenticateBearer("invalid")).thenThrow(new IllegalArgumentException("invalid token"));
        assertThatThrownBy(() -> controller.scan("invalid", new UserCardController.ScanRequest("surprising://user/42")))
                .isInstanceOf(ResponseStatusException.class);
        verifyNoInteractions(users);
    }
}
