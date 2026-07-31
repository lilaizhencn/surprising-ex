package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class AdminComplianceControllerTest {

    @Test
    void usersRequiresComplianceReadPermission() {
        AuthService authService = mock(AuthService.class);
        ComplianceService service = mock(ComplianceService.class);
        ComplianceUserSummary user = new ComplianceUserSummary(
                42L, "alice", "NORMAL", "STANDARD", "VERIFIED", "SG", 1, 0, Instant.now());
        when(service.usersPage(null, "VERIFIED", null, 100, "cursor", "updatedAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(user), "next", true,
                        "updatedAt.asc", 100));
        AdminComplianceController controller = controller(authService, service, new FakeApprovalRepository());

        var response = controller.users("Bearer admin", null, "VERIFIED", null, 100,
                "cursor", "updatedAt.asc");

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.asc");
        assertThat(response.limit()).isEqualTo(100);
        verify(authService).requireAdminPermission("Bearer admin", "admin.compliance.read");
        verify(service).usersPage(null, "VERIFIED", null, 100, "cursor", "updatedAt.asc");
    }

    @Test
    void riskTagsReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        ComplianceService service = mock(ComplianceService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        RiskTag tag = new RiskTag(11L, 42L, "HIGH_RISK", "HIGH", "ACTIVE", "MANUAL",
                "review", 7L, null, now, null, now);
        when(service.riskTagsPage(42L, "ACTIVE", 50, "cursor", "updatedAt.desc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(tag), "next", true,
                        "updatedAt.desc", 50));
        AdminComplianceController controller = controller(authService, service, new FakeApprovalRepository());

        var response = controller.riskTags("Bearer admin", 42L, "ACTIVE", 50,
                "cursor", "updatedAt.desc");

        assertThat(response.tags()).containsExactly(tag);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.desc");
        assertThat(response.limit()).isEqualTo(50);
        verify(authService).requireAdminPermission("Bearer admin", "admin.compliance.read");
        verify(service).riskTagsPage(42L, "ACTIVE", 50, "cursor", "updatedAt.desc");
    }

    @Test
    void amlCasesReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        ComplianceService service = mock(ComplianceService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        AmlCase amlCase = new AmlCase(21L, 42L, "REVIEWING", 75, "MANUAL",
                "case summary", null, 7L, null, null, null, now, now);
        when(service.amlCasesPage(42L, "REVIEWING", 50, "cursor", "createdAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(amlCase), "next", true,
                        "createdAt.asc", 50));
        AdminComplianceController controller = controller(authService, service, new FakeApprovalRepository());

        var response = controller.amlCases("Bearer admin", 42L, "REVIEWING", 50,
                "cursor", "createdAt.asc");

        assertThat(response.cases()).containsExactly(amlCase);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(authService).requireAdminPermission("Bearer admin", "admin.compliance.read");
        verify(service).amlCasesPage(42L, "REVIEWING", 50, "cursor", "createdAt.asc");
    }

    @Test
    void updateKycConsumesApprovalAndWritesReviewedAdmin() {
        AuthService authService = mock(AuthService.class);
        ComplianceService service = mock(ComplianceService.class);
        FakeApprovalRepository approvalRepository = new FakeApprovalRepository();
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer admin", "admin.compliance.write"))
                .thenReturn(new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), now.plusSeconds(60)));
        when(authService.adminUser("Bearer admin", 42L)).thenReturn(new AuthenticatedUser(
                42L, "alice", "alice@example.com", "NORMAL", List.of("USER"), now));
        KycProfile profile = new KycProfile(42L, "STANDARD", "VERIFIED", "SG", "PASSPORT",
                "manual", "case-1", 7L, now, null, null, now, now);
        when(service.upsertKyc(eq(42L), eq(7L), any(), any())).thenReturn(profile);
        AdminComplianceController controller = controller(authService, service, approvalRepository);
        MockHttpServletRequest request = new MockHttpServletRequest(
                "POST", "/api/v1/admin/compliance/users/42/kyc");
        request.addHeader("X-Admin-Approval-Id", "77");
        byte[] body = """
                {"kycLevel":"STANDARD","status":"VERIFIED","country":"SG","documentType":"PASSPORT","provider":"manual","providerReference":"case-1"}
                """.getBytes();

        var response = controller.updateKyc("Bearer admin", 42L, body, request);

        assertThat(response.status()).isEqualTo("VERIFIED");
        assertThat(approvalRepository.approvalId).isEqualTo(77L);
        assertThat(approvalRepository.requesterUserId).isEqualTo(7L);
        assertThat(approvalRepository.service).isEqualTo("gateway-admin");
        assertThat(approvalRepository.path).isEqualTo("/api/v1/admin/compliance/users/42/kyc");
        assertThat(approvalRepository.bodyHash).hasSize(64);
        verify(service).upsertKyc(eq(42L), eq(7L), any(), any());
    }

    private AdminComplianceController controller(AuthService authService,
                                                 ComplianceService service,
                                                 AdminApprovalRepository approvalRepository) {
        return new AdminComplianceController(authService, service, approvalRepository,
                new GatewayProperties(), new ObjectMapper());
    }

    private static final class FakeApprovalRepository extends AdminApprovalRepository {
        private long approvalId;
        private long requesterUserId;
        private String service;
        private String path;
        private String bodyHash;

        private FakeApprovalRepository() {
            super(null);
        }

        @Override
        public AuthModels.AdminApprovalResponse consumeApproved(long approvalId,
                                                                long requesterUserId,
                                                                String service,
                                                                String method,
                                                                String requestPath,
                                                                String queryString,
                                                                String bodyHash,
                                                                String traceId,
                                                                Instant now) {
            this.approvalId = approvalId;
            this.requesterUserId = requesterUserId;
            this.service = service;
            this.path = requestPath;
            this.bodyHash = bodyHash;
            return null;
        }
    }
}
