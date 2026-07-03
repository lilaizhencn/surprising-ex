package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobQueryResponse;
import com.surprising.gateway.provider.auth.AuthModels.AdminExportJobResponse;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class AdminExportControllerTest {

    @Test
    void exportsDelegatesWithCursorAndSort() {
        AuthService authService = mock(AuthService.class);
        AdminExportRepository repository = mock(AdminExportRepository.class);
        AdminExportService exportService = mock(AdminExportService.class);
        AdminApprovalRepository approvalRepository = mock(AdminApprovalRepository.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        AdminExportJobResponse export = new AdminExportJobResponse(
                91L, 7L, "ops", "USERS", "PENDING", "CSV", "{}",
                null, null, 0, 0, null, now, null, null, now.plusSeconds(604800));
        when(repository.jobPage("PENDING", "USERS", 2, "cursor-1", "requestedAt.asc"))
                .thenReturn(new AdminCursorPage.CursorPage<>(List.of(export),
                        "cursor-2", true, "requestedAt.asc", 2));
        AdminExportController controller = new AdminExportController(authService, repository, exportService,
                approvalRepository, new GatewayProperties(), new ObjectMapper());

        AdminExportJobQueryResponse response = controller.exports(
                "Bearer admin", "PENDING", "USERS", 2, "cursor-1", "requestedAt.asc");

        assertThat(response.exports()).containsExactly(export);
        assertThat(response.nextCursor()).isEqualTo("cursor-2");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("requestedAt.asc");
        assertThat(response.limit()).isEqualTo(2);
        verify(authService).requireAdminPermission("Bearer admin", "admin.exports.read");
    }

    @Test
    void createConsumesApprovalAndStartsExportJob() {
        AuthService authService = mock(AuthService.class);
        AdminExportRepository repository = mock(AdminExportRepository.class);
        AdminExportService exportService = mock(AdminExportService.class);
        FakeApprovalRepository approvalRepository = new FakeApprovalRepository();
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        JwtPrincipal principal = new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), now.plusSeconds(60));
        when(authService.requireAdminPermission("Bearer admin", "admin.exports.write")).thenReturn(principal);
        when(exportService.create(eq(principal), any(), any())).thenReturn(new AdminExportJobResponse(
                88L, 7L, "ops", "USERS", "PENDING", "CSV", "{\"limit\":\"10\"}",
                null, null, 0, 0, null, now, null, null, now.plusSeconds(604800)));
        AdminExportController controller = new AdminExportController(authService, repository, exportService,
                approvalRepository, new GatewayProperties(), new ObjectMapper());
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/exports");
        request.addHeader("X-Admin-Approval-Id", "77");
        byte[] body = """
                {"exportType":"USERS","params":{"limit":"10"}}
                """.getBytes();

        AdminExportJobResponse response = controller.create("Bearer admin", body, request);

        assertThat(response.exportId()).isEqualTo(88L);
        assertThat(approvalRepository.approvalId).isEqualTo(77L);
        assertThat(approvalRepository.requesterUserId).isEqualTo(7L);
        assertThat(approvalRepository.service).isEqualTo("gateway-admin");
        assertThat(approvalRepository.path).isEqualTo("/api/v1/admin/exports");
        assertThat(approvalRepository.bodyHash).hasSize(64);
        verify(exportService).create(eq(principal), any(), any());
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
