package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertEvaluationResponse;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertChannel;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertChannelRequest;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertDelivery;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertEvent;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertRule;
import com.surprising.gateway.provider.auth.AdminAlertRepository.AlertRuleRequest;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

class AdminAlertControllerTest {

    @Test
    void rulesRequireAlertsReadPermission() {
        AuthService authService = mock(AuthService.class);
        FakeAlertRepository repository = new FakeAlertRepository();
        AdminAlertController controller = controller(authService, repository, new FakeApprovalRepository());

        var response = controller.rules("Bearer admin", "MARKET", true, 50,
                "cursor", "updatedAt.asc");

        assertThat(response.count()).isEqualTo(1);
        assertThat(response.rules().get(0).ruleCode()).isEqualTo("MARK_DEVIATION_WARN");
        assertThat(response.nextCursor()).isEqualTo("next-rules");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        assertThat(repository.ruleCursor).isEqualTo("cursor");
        assertThat(repository.ruleSort).isEqualTo("updatedAt.asc");
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.read");
    }

    @Test
    void createRuleRequiresAlertsWritePermission() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        JwtPrincipal principal = new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), now.plusSeconds(60));
        when(authService.requireAdminPermission("Bearer admin", "admin.alerts.write")).thenReturn(principal);
        FakeAlertRepository repository = new FakeAlertRepository();
        FakeApprovalRepository approvalRepository = new FakeApprovalRepository();
        AdminAlertController controller = controller(authService, repository, approvalRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/alerts/rules");
        request.addHeader("X-Admin-Approval-Id", "77");
        byte[] body = """
                {"ruleCode":"MARK_DEVIATION_WARN","ruleName":"Mark deviation","domain":"MARKET","metricKey":"MARK_INDEX_DEVIATION_PPM","target":"BTC-USDT","conditionOperator":"GT","thresholdValue":5000,"severity":"WARN","enabled":true,"windowSeconds":300,"cooldownSeconds":300,"description":"mark/index deviation"}
                """.getBytes();

        var response = controller.createRule("Bearer admin", body, request);

        assertThat(response.createdByUserId()).isEqualTo(7L);
        assertThat(repository.created).isTrue();
        assertThat(approvalRepository.approvalId).isEqualTo(77L);
        assertThat(approvalRepository.requesterUserId).isEqualTo(7L);
        assertThat(approvalRepository.service).isEqualTo("gateway-admin");
        assertThat(approvalRepository.path).isEqualTo("/api/v1/admin/alerts/rules");
        assertThat(approvalRepository.bodyHash).hasSize(64);
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.write");
    }

    @Test
    void createChannelRequiresAlertsWritePermissionAndApproval() {
        AuthService authService = mock(AuthService.class);
        Instant now = Instant.parse("2026-07-03T00:00:00Z");
        JwtPrincipal principal = new JwtPrincipal(7L, "ops", "NORMAL", List.of("ADMIN"), now.plusSeconds(60));
        when(authService.requireAdminPermission("Bearer admin", "admin.alerts.write")).thenReturn(principal);
        FakeAlertRepository repository = new FakeAlertRepository();
        FakeApprovalRepository approvalRepository = new FakeApprovalRepository();
        AdminAlertController controller = controller(authService, repository, approvalRepository);
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/admin/alerts/channels");
        request.addHeader("X-Admin-Approval-Id", "88");
        byte[] body = """
                {"channelCode":"OPS_WEBHOOK","channelName":"Ops webhook","channelType":"WEBHOOK","enabled":true,"domain":"SYSTEM","minSeverity":"WARN","endpoint":"https://ops.example.com/alerts","description":"ops notifications"}
                """.getBytes();

        var response = controller.createChannel("Bearer admin", body, request);

        assertThat(response.createdByUserId()).isEqualTo(7L);
        assertThat(repository.channelCreated).isTrue();
        assertThat(approvalRepository.approvalId).isEqualTo(88L);
        assertThat(approvalRepository.path).isEqualTo("/api/v1/admin/alerts/channels");
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.write");
    }

    @Test
    void channelsReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        FakeAlertRepository repository = new FakeAlertRepository();
        AdminAlertController controller = controller(authService, repository, new FakeApprovalRepository());

        var response = controller.channels("Bearer admin", "SYSTEM", true, 25,
                "cursor", "createdAt.desc");

        assertThat(response.channels()).singleElement()
                .satisfies(channel -> assertThat(channel.alertChannelId()).isEqualTo(21L));
        assertThat(response.nextCursor()).isEqualTo("next-channels");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.desc");
        assertThat(response.limit()).isEqualTo(25);
        assertThat(repository.channelCursor).isEqualTo("cursor");
        assertThat(repository.channelSort).isEqualTo("createdAt.desc");
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.read");
    }


    @Test
    void evaluateRequiresAlertsWritePermission() {
        AuthService authService = mock(AuthService.class);
        FakeAlertRepository repository = new FakeAlertRepository();
        AdminAlertController controller = controller(authService, repository, new FakeApprovalRepository());

        var response = controller.evaluate("Bearer admin");

        assertThat(response.evaluatedRules()).isEqualTo(1);
        assertThat(repository.evaluated).isTrue();
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.write");
    }

    @Test
    void eventsReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        FakeAlertRepository repository = new FakeAlertRepository();
        AdminAlertController controller = controller(authService, repository, new FakeApprovalRepository());

        var response = controller.events("Bearer admin", "OPEN", "WARN", "SYSTEM", 25,
                "cursor", "lastSeenAt.asc");

        assertThat(response.events()).singleElement()
                .satisfies(event -> assertThat(event.alertEventId()).isEqualTo(31L));
        assertThat(response.nextCursor()).isEqualTo("next-events");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("lastSeenAt.asc");
        assertThat(response.limit()).isEqualTo(25);
        assertThat(repository.eventCursor).isEqualTo("cursor");
        assertThat(repository.eventSort).isEqualTo("lastSeenAt.asc");
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.read");
    }

    @Test
    void deliveriesReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        FakeAlertRepository repository = new FakeAlertRepository();
        AdminAlertController controller = controller(authService, repository, new FakeApprovalRepository());

        var response = controller.deliveries("Bearer admin", "FAILED", 12L, 31L, 10,
                "cursor", "updatedAt.desc");

        assertThat(response.deliveries()).singleElement()
                .satisfies(delivery -> assertThat(delivery.alertDeliveryId()).isEqualTo(41L));
        assertThat(response.nextCursor()).isEqualTo("next-deliveries");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.desc");
        assertThat(response.limit()).isEqualTo(10);
        assertThat(repository.deliveryCursor).isEqualTo("cursor");
        assertThat(repository.deliverySort).isEqualTo("updatedAt.desc");
        verify(authService).requireAdminPermission("Bearer admin", "admin.alerts.read");
    }

    private AdminAlertController controller(AuthService authService,
                                            AdminAlertRepository repository,
                                            AdminApprovalRepository approvalRepository) {
        return new AdminAlertController(authService, repository, approvalRepository,
                new GatewayProperties(), new ObjectMapper());
    }

    private static final class FakeAlertRepository extends AdminAlertRepository {
        private boolean created;
        private boolean channelCreated;
        private boolean evaluated;
        private String ruleCursor;
        private String ruleSort;
        private String channelCursor;
        private String channelSort;
        private String eventCursor;
        private String eventSort;
        private String deliveryCursor;
        private String deliverySort;

        private FakeAlertRepository() {
            super(null);
        }

        @Override
        public AdminCursorPage.CursorPage<AlertRule> rulesPage(String domain,
                                                               Boolean enabled,
                                                               int limit,
                                                               String cursor,
                                                               String sort) {
            ruleCursor = cursor;
            ruleSort = sort;
            return new AdminCursorPage.CursorPage<>(List.of(rule(0L)), "next-rules", true,
                    "updatedAt.asc", limit);
        }

        @Override
        public AlertRule createRule(JwtPrincipal principal, AlertRuleRequest request, Instant now) {
            created = true;
            return rule(principal.userId());
        }

        @Override
        public AlertChannel createChannel(JwtPrincipal principal, AlertChannelRequest request, Instant now) {
            channelCreated = true;
            return new AlertChannel(21L, "OPS_WEBHOOK", "Ops webhook", "WEBHOOK", true,
                    "SYSTEM", "WARN", "https://ops.example.com/alerts", "ops notifications",
                    principal.userId(), principal.userId(), now, now);
        }

        @Override
        public AdminCursorPage.CursorPage<AlertChannel> channelsPage(String domain,
                                                                     Boolean enabled,
                                                                     int limit,
                                                                     String cursor,
                                                                     String sort) {
            channelCursor = cursor;
            channelSort = sort;
            Instant now = Instant.parse("2026-07-03T00:00:00Z");
            AlertChannel channel = new AlertChannel(21L, "OPS_WEBHOOK", "Ops webhook", "WEBHOOK", true,
                    "SYSTEM", "WARN", "https://ops.example.com/alerts", "ops notifications",
                    7L, 7L, now, now);
            return new AdminCursorPage.CursorPage<>(List.of(channel), "next-channels", true,
                    "createdAt.desc", limit);
        }

        @Override
        public AlertEvaluationResponse evaluate(Instant now) {
            evaluated = true;
            return new AlertEvaluationResponse(1, 0, 0, List.of());
        }

        @Override
        public AdminCursorPage.CursorPage<AlertEvent> eventsPage(String status,
                                                                 String severity,
                                                                 String domain,
                                                                 int limit,
                                                                 String cursor,
                                                                 String sort) {
            eventCursor = cursor;
            eventSort = sort;
            Instant now = Instant.parse("2026-07-03T00:00:00Z");
            AlertEvent event = new AlertEvent(31L, 11L, "MARK_DEVIATION_WARN", "SYSTEM",
                    "MARK_INDEX_DEVIATION_PPM", "BTC-USDT", "WARN", "OPEN", "GT",
                    BigDecimal.valueOf(5000), BigDecimal.valueOf(7000), "fp", "mark deviation",
                    2L, now.minusSeconds(60), now, null, null, null, now.minusSeconds(60), now);
            return new AdminCursorPage.CursorPage<>(List.of(event), "next-events", true,
                    "lastSeenAt.asc", limit);
        }

        @Override
        public AdminCursorPage.CursorPage<AlertDelivery> deliveriesPage(String status,
                                                                        Long channelId,
                                                                        Long eventId,
                                                                        int limit,
                                                                        String cursor,
                                                                        String sort) {
            deliveryCursor = cursor;
            deliverySort = sort;
            Instant now = Instant.parse("2026-07-03T00:00:00Z");
            AlertDelivery delivery = new AlertDelivery(41L, 31L, 12L, "OPS_WEBHOOK", "WEBHOOK",
                    "FAILED", 3, now.plusSeconds(60), now.minusSeconds(30), null, "timeout",
                    "MARK_DEVIATION_WARN", "SYSTEM", "WARN", "OPEN", "mark deviation", now, now);
            return new AdminCursorPage.CursorPage<>(List.of(delivery), "next-deliveries", true,
                    "updatedAt.desc", limit);
        }

        private AlertRule rule(long createdByUserId) {
            Instant now = Instant.parse("2026-07-03T00:00:00Z");
            return new AlertRule(11L, "MARK_DEVIATION_WARN", "Mark deviation",
                    "MARKET", "MARK_INDEX_DEVIATION_PPM", "BTC-USDT", "GT",
                    BigDecimal.valueOf(5000), "WARN", true, 300L, 300L,
                    "mark/index deviation", createdByUserId, createdByUserId, now, now);
        }
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
