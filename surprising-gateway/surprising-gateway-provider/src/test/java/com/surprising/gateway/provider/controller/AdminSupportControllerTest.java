package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceRepository;
import com.surprising.gateway.provider.auth.ComplianceRepository.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceRepository.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceRepository.RiskTag;
import com.surprising.gateway.provider.auth.SupportTicketRepository;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicket;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicketNote;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.client.RestTemplate;

class AdminSupportControllerTest {

    @Test
    void overviewRequiresSupportPermissionAndReturnsReadOnlyUserContext() {
        AuthService authService = mock(AuthService.class);
        ComplianceRepository complianceRepository = mock(ComplianceRepository.class);
        CapturingRestTemplate restTemplate = new CapturingRestTemplate();
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        when(authService.adminUser("Bearer support", 1001L))
                .thenReturn(new AuthenticatedUser(1001L, "alice", "alice@example.com", "NORMAL",
                        List.of("USER"), now));
        when(complianceRepository.kyc(1001L)).thenReturn(new KycProfile(
                1001L, "STANDARD", "VERIFIED", "SG", "PASSPORT", "manual", "case-1",
                7L, now, null, now.plusSeconds(86400), now, now));
        when(complianceRepository.riskTags(1001L, "ACTIVE", 100)).thenReturn(List.of(
                new RiskTag(1L, 1001L, "AML_REVIEW", "CRITICAL", "ACTIVE", "manual",
                        "review", 7L, null, now, null, now)));
        when(complianceRepository.amlCases(1001L, null, 100)).thenReturn(List.of(
                new AmlCase(2L, 1001L, "REVIEWING", 75, "manual", "case",
                        null, 7L, null, null, null, now, now)));
        AdminSupportController controller = new AdminSupportController(
                authService, complianceRepository, mock(SupportTicketRepository.class), properties(), restTemplate);
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("X-Request-Id", "request-1");

        var response = controller.overview("Bearer support", 1001L, "usdt", 25, request);

        assertThat(response.user().userId()).isEqualTo(1001L);
        assertThat(response.user().status()).isEqualTo("NORMAL");
        assertThat(response.compliance().kycStatus()).isEqualTo("VERIFIED");
        assertThat(response.compliance().criticalRiskTags()).isEqualTo(1);
        assertThat(response.compliance().openAmlCases()).isEqualTo(1);
        assertThat(response.errors()).isEmpty();
        assertThat(restTemplate.urls).extracting(URI::toString)
                .contains("http://account:9086/api/v1/admin/accounts/balances?userId=1001");
        assertThat(restTemplate.urls).extracting(URI::toString)
                .anySatisfy(uri -> assertThat(uri)
                        .startsWith("http://order:9084/api/v1/admin/trading/orders?")
                        .contains("userId=1001")
                        .contains("limit=25"));
        assertThat(restTemplate.requestEntities.get(0).getHeaders().getFirst("X-Admin-Roles"))
                .isEqualTo("SUPPORT");
        assertThat(restTemplate.requestEntities.get(0).getHeaders().getFirst("X-Request-Id"))
                .isEqualTo("request-1");
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
    }

    @Test
    void supportCanCreateTicketWithInitialInternalNote() {
        AuthService authService = mock(AuthService.class);
        ComplianceRepository complianceRepository = mock(ComplianceRepository.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.write"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        when(authService.adminUser("Bearer support", 1001L))
                .thenReturn(new AuthenticatedUser(1001L, "alice", "alice@example.com", "NORMAL",
                        List.of("USER"), now));
        SupportTicket ticket = new SupportTicket(501L, 1001L, "OPEN", "HIGH", "WITHDRAWAL",
                "Withdrawal delayed", 9L, 9L, null, now, now, null);
        SupportTicketNote note = new SupportTicketNote(701L, 501L, 9L, "NOTE", "INTERNAL",
                "checking wallet tx", now);
        when(supportTicketRepository.createTicket(eq(1001L), eq("HIGH"), eq("WITHDRAWAL"),
                eq("Withdrawal delayed"), eq(9L), eq(9L), any(Instant.class))).thenReturn(ticket);
        when(supportTicketRepository.addNote(eq(501L), eq(9L), eq("NOTE"), eq("INTERNAL"),
                eq("checking wallet tx"), any(Instant.class))).thenReturn(note);
        AdminSupportController controller = new AdminSupportController(
                authService, complianceRepository, supportTicketRepository, properties(), new CapturingRestTemplate());

        var response = controller.createTicket("Bearer support", 1001L,
                new AdminSupportController.CreateSupportTicketRequest(
                        "Withdrawal delayed", "withdrawal", "high", 9L, "checking wallet tx"));

        assertThat(response.ticket().ticketId()).isEqualTo(501L);
        assertThat(response.notes()).containsExactly(note);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.write");
        verify(authService).adminUser("Bearer support", 1001L);
    }

    @Test
    void ticketsReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        SupportTicket ticket = new SupportTicket(501L, 1001L, "OPEN", "HIGH", "WITHDRAWAL",
                "Withdrawal delayed", 9L, 9L, null, now, now, null);
        when(supportTicketRepository.ticketsPage(1001L, "OPEN", 50, "cursor", "updatedAt.asc"))
                .thenReturn(new SupportTicketRepository.CursorPage<>(List.of(ticket), "next", true,
                        "updatedAt.asc", 50));
        AdminSupportController controller = new AdminSupportController(
                authService, mock(ComplianceRepository.class), supportTicketRepository,
                properties(), new CapturingRestTemplate());

        var response = controller.tickets("Bearer support", 1001L, "OPEN", 50,
                "cursor", "updatedAt.asc");

        assertThat(response.tickets()).containsExactly(ticket);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
        verify(supportTicketRepository).ticketsPage(1001L, "OPEN", 50, "cursor", "updatedAt.asc");
    }

    @Test
    void notesReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        SupportTicketRepository supportTicketRepository = mock(SupportTicketRepository.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        SupportTicketNote note = new SupportTicketNote(701L, 501L, 9L, "NOTE", "INTERNAL",
                "checking wallet tx", now);
        when(supportTicketRepository.notesPage(501L, 25, "cursor", "createdAt.desc"))
                .thenReturn(new SupportTicketRepository.CursorPage<>(List.of(note), "next", true,
                        "createdAt.desc", 25));
        AdminSupportController controller = new AdminSupportController(
                authService, mock(ComplianceRepository.class), supportTicketRepository,
                properties(), new CapturingRestTemplate());

        var response = controller.notes("Bearer support", 501L, 25, "cursor", "createdAt.desc");

        assertThat(response.notes()).containsExactly(note);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.desc");
        assertThat(response.limit()).isEqualTo(25);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
        verify(supportTicketRepository).notesPage(501L, 25, "cursor", "createdAt.desc");
    }

    private GatewayProperties properties() {
        GatewayProperties properties = new GatewayProperties();
        Map<String, GatewayProperties.BackendRoute> adminRoutes = new LinkedHashMap<>();
        adminRoutes.put("account", new GatewayProperties.BackendRoute(
                "http://account:9086", "/api/v1/admin/accounts", true));
        adminRoutes.put("trading-orders", new GatewayProperties.BackendRoute(
                "http://order:9084", "/api/v1/admin/trading/orders", true));
        adminRoutes.put("trading-trigger", new GatewayProperties.BackendRoute(
                "http://trigger:9095", "/api/v1/admin/trading/trigger-orders", true));
        adminRoutes.put("risk", new GatewayProperties.BackendRoute(
                "http://risk:9087", "/api/v1/risk", true));
        properties.setAdminRoutes(adminRoutes);
        return properties;
    }

    private static final class CapturingRestTemplate extends RestTemplate {
        private final List<URI> urls = new ArrayList<>();
        private final List<HttpEntity<?>> requestEntities = new ArrayList<>();

        @Override
        public <T> ResponseEntity<T> exchange(URI url,
                                              HttpMethod method,
                                              HttpEntity<?> requestEntity,
                                              Class<T> responseType) {
            urls.add(url);
            requestEntities.add(requestEntity);
            Map<String, Object> body = Map.of("uri", url.toString());
            return ResponseEntity.ok(responseType.cast(body));
        }
    }
}
