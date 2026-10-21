package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.surprising.gateway.provider.auth.controller.AdminAuthController;
import com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding;
import com.surprising.gateway.provider.auth.model.AuthModels.*;
import com.surprising.gateway.provider.auth.repository.*;
import com.surprising.gateway.provider.auth.service.*;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.ObjectMapper;

class AdminApprovalHeaderTest {
    private static final String PATH = "/api/v1/admin/users/7/status";
    private static final byte[] BODY = "{\"status\":\"FROZEN\"}".getBytes(StandardCharsets.UTF_8);
    private final GatewayProperties properties = new GatewayProperties();
    private final AuthService auth = mock(AuthService.class);
    private final AdminApprovalRepository rows = mock(AdminApprovalRepository.class);
    private MockMvc mvc;

    @BeforeEach void setup() {
        properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(true);
        properties.getSecurity().setAdminApprovalHeader("X-QA-Approval");
        when(auth.authenticateAdminBearer("admin")).thenReturn(new JwtPrincipal(7, "admin", "NORMAL", List.of("ADMIN"), Instant.now().plusSeconds(3600)));
        when(auth.updateUserStatus("admin", 7L, "FROZEN")).thenReturn(new AuthenticatedUser(7, "user", null, "FROZEN", List.of("USER"), Instant.now()));
        var service = new AdminApprovalService(auth, mock(AdminAuditRepository.class), rows, properties);
        mvc = MockMvcBuilders.standaloneSetup(new AdminAuthController(auth, service, new ObjectMapper())).build();
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "bad-header", "9223372036854775808", "-7"})
    void disabledPolicyIgnoresMalformedOrMissingOptionalHeader(String header) throws Exception {
        properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(false);
        perform(header, 200);
        verify(auth).updateUserStatus("admin", 7L, "FROZEN");
        verifyNoInteractions(rows);
    }

    @ParameterizedTest @NullAndEmptySource @ValueSource(strings = {" ", "bad-header", "9223372036854775808"})
    void requiredPolicyRejectsUnusableHeaderBeforeRepository(String header) throws Exception {
        perform(header, 428);
        verifyNoInteractions(rows);
        verify(auth, never()).updateUserStatus(anyString(), anyLong(), anyString());
    }

    @ParameterizedTest @ValueSource(longs = {9007199254740993L, Long.MAX_VALUE})
    void controllerBindsExactLongAndPreservesLocalWhitespaceFormat(long id) throws Exception {
        Instant now = Instant.now();
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(BODY));
        var approved = new AdminApprovalResponse(id, 7L, "admin", 8L, "reviewer", "gateway-admin", "POST", PATH,
                null, hash, "review", "ok", "APPROVED", now, now.plusSeconds(3600), now, null);
        when(rows.findForUpdate(id)).thenReturn(Optional.of(approved));
        perform(" " + id + " ", 200);
        verify(rows).findForUpdate(id);
        verify(rows).markConsumed(eq(id), any());
    }

    @Test void bindingRetainsLocalAndC2cFormatDistinctionWithoutRawStrings() {
        for (String header : List.of(" 7 ", "+7", "07", "-7")) {
            var reference = AdminApprovalRequestBinding.reference(header);
            assertThat(reference.supplied()).isTrue();
            assertThat(reference.value()).isNotNull();
            assertThat(reference.canonicalPositive()).isFalse();
        }
        var valid = AdminApprovalRequestBinding.reference(Long.toString(Long.MAX_VALUE));
        assertThat(valid.value()).isEqualTo(Long.MAX_VALUE);
        assertThat(valid.canonicalPositive()).isTrue();
    }

    private void perform(String header, int expectedStatus) throws Exception {
        var request = post(PATH).header("Authorization", "admin").contentType("application/json").content(BODY);
        if (header != null) request.header("X-QA-Approval", header);
        mvc.perform(request).andExpect(status().is(expectedStatus));
    }
}
