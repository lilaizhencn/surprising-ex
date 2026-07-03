package com.surprising.gateway.provider.controller;

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
import com.surprising.gateway.provider.config.GatewayProperties.BackendRoute;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.util.UriComponentsBuilder;

@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final AuthService authService;
    private final ComplianceRepository complianceRepository;
    private final SupportTicketRepository supportTicketRepository;
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;

    public AdminSupportController(AuthService authService,
                                  ComplianceRepository complianceRepository,
                                  SupportTicketRepository supportTicketRepository,
                                  GatewayProperties properties,
                                  RestTemplate restTemplate) {
        this.authService = authService;
        this.complianceRepository = complianceRepository;
        this.supportTicketRepository = supportTicketRepository;
        this.properties = properties;
        this.restTemplate = restTemplate;
    }

    @GetMapping("/users/{userId}/overview")
    public SupportUserOverviewResponse overview(@RequestHeader("Authorization") String authorization,
                                                @PathVariable("userId") long userId,
                                                @RequestParam(value = "settleAsset", defaultValue = "USDT") String settleAsset,
                                                @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                                HttpServletRequest request) {
        JwtPrincipal principal = supportPrincipal(authorization);
        AuthenticatedUser user = user(authorization, userId);
        int boundedLimit = boundLimit(limit);
        String normalizedSettleAsset = normalizeAsset(settleAsset);

        List<SupportOverviewError> errors = new ArrayList<>();
        SupportDownstreamContext context = new SupportDownstreamContext(principal, request, errors);
        SupportAccountOverview account = new SupportAccountOverview(
                fetch(context, "account", "balances", "/balances", Map.of("userId", userId)),
                fetch(context, "account", "productBalances", "/product-balances", Map.of("userId", userId)),
                fetch(context, "account", "positions", "/positions", Map.of("userId", userId)),
                fetch(context, "account", "transfers", "/transfers", Map.of("userId", userId, "limit", boundedLimit)));
        SupportTradingOverview trading = new SupportTradingOverview(
                fetch(context, "trading-orders", "orders", "", Map.of("userId", userId, "limit", boundedLimit)),
                fetch(context, "trading-orders", "trades", "/trades", Map.of("userId", userId, "limit", boundedLimit)),
                fetch(context, "trading-trigger", "triggerOrders", "", Map.of("userId", userId, "limit", boundedLimit)));
        SupportRiskOverview risk = new SupportRiskOverview(
                fetch(context, "risk", "accountLatest", "/account/latest",
                        Map.of("userId", userId, "settleAsset", normalizedSettleAsset)),
                fetch(context, "risk", "positionsLatest", "/positions/latest", Map.of("userId", userId)));

        return new SupportUserOverviewResponse(
                Instant.now(),
                new SupportUserSummary(user.userId(), user.username(), user.email(), user.status(), user.createdAt()),
                compliance(userId),
                account,
                trading,
                risk,
                errors);
    }

    @GetMapping("/tickets")
    public SupportTicketQueryResponse tickets(@RequestHeader("Authorization") String authorization,
                                              @RequestParam(value = "userId", required = false) Long userId,
                                              @RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                              @RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "sort", required = false) String sort) {
        supportPrincipal(authorization);
        String normalizedStatus = status == null || status.isBlank() ? null : normalizeTicketStatus(status);
        var page = supportTicketRepository.ticketsPage(
                userId, normalizedStatus, boundLimit(limit), cursor, sort);
        return new SupportTicketQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @PostMapping("/users/{userId}/tickets")
    @Transactional
    public SupportTicketDetailResponse createTicket(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable("userId") long userId,
                                                    @RequestBody CreateSupportTicketRequest request) {
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        user(authorization, userId);
        CreateSupportTicketRequest body = request == null
                ? new CreateSupportTicketRequest(null, null, null, null, null)
                : request;
        Instant now = Instant.now();
        SupportTicket ticket = supportTicketRepository.createTicket(userId, normalizePriority(body.priority()),
                normalizeCode(body.category(), "GENERAL", "category"), requireText(body.title(), "title", 160),
                body.assignedAdminUserId(), principal.userId(), now);
        List<SupportTicketNote> notes = new ArrayList<>();
        if (body.initialNote() != null && !body.initialNote().isBlank()) {
            notes.add(supportTicketRepository.addNote(ticket.ticketId(), principal.userId(), "NOTE", "INTERNAL",
                    requireText(body.initialNote(), "initialNote", 2000), now));
        }
        return new SupportTicketDetailResponse(ticket, notes.size(), notes);
    }

    @GetMapping("/tickets/{ticketId}/notes")
    public SupportTicketNotesResponse notes(@RequestHeader("Authorization") String authorization,
                                            @PathVariable("ticketId") long ticketId,
                                            @RequestParam(value = "limit", defaultValue = "200") int limit,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "sort", required = false) String sort) {
        supportPrincipal(authorization);
        var page = supportTicketRepository.notesPage(ticketId, Math.min(Math.max(limit, 1), 500), cursor, sort);
        return new SupportTicketNotesResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    @PostMapping("/tickets/{ticketId}/notes")
    public SupportTicketNote addNote(@RequestHeader("Authorization") String authorization,
                                     @PathVariable("ticketId") long ticketId,
                                     @RequestBody SupportTicketNoteRequest request) {
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        SupportTicketNoteRequest body = request == null
                ? new SupportTicketNoteRequest(null, null, null)
                : request;
        try {
            return supportTicketRepository.addNote(ticketId, principal.userId(), normalizeNoteType(body.noteType()),
                    normalizeVisibility(body.visibility()), requireText(body.body(), "body", 2000), Instant.now());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "support ticket not found", ex);
        }
    }

    @PostMapping("/tickets/{ticketId}/status")
    @Transactional
    public SupportTicketDetailResponse updateStatus(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable("ticketId") long ticketId,
                                                    @RequestBody SupportTicketStatusRequest request) {
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        SupportTicketStatusRequest body = request == null
                ? new SupportTicketStatusRequest(null, null)
                : request;
        String status = normalizeTicketStatus(body.status());
        String reason = requireText(body.reason(), "reason", 2000);
        Instant now = Instant.now();
        try {
            SupportTicket ticket = supportTicketRepository.updateStatus(ticketId, status, principal.userId(), now);
            SupportTicketNote note = supportTicketRepository.addNote(ticketId, principal.userId(), "STATUS_CHANGE",
                    "INTERNAL", reason, now);
            return new SupportTicketDetailResponse(ticket, 1, List.of(note));
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "support ticket not found", ex);
        }
    }

    private JwtPrincipal supportPrincipal(String authorization) {
        return supportPrincipal(authorization, "admin.support.read");
    }

    private JwtPrincipal supportPrincipal(String authorization, String permission) {
        try {
            return authService.requireAdminPermission(authorization, permission);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private AuthenticatedUser user(String authorization, long userId) {
        try {
            return authService.adminUser(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        }
    }

    private SupportComplianceSummary compliance(long userId) {
        KycProfile kyc = complianceRepository.kyc(userId);
        List<RiskTag> activeTags = complianceRepository.riskTags(userId, "ACTIVE", 100);
        List<AmlCase> openCases = complianceRepository.amlCases(userId, null, 100).stream()
                .filter(item -> !"CLOSED".equals(item.status()) && !"CLEARED".equals(item.status()))
                .toList();
        long criticalTags = activeTags.stream().filter(item -> "CRITICAL".equals(item.severity())).count();
        int maxAmlRiskScore = openCases.stream().mapToInt(AmlCase::riskScore).max().orElse(0);
        return new SupportComplianceSummary(
                kyc == null ? "NONE" : kyc.kycLevel(),
                kyc == null ? "UNVERIFIED" : kyc.status(),
                kyc == null ? null : kyc.country(),
                kyc == null ? null : kyc.expiresAt(),
                activeTags.size(),
                criticalTags,
                openCases.size(),
                maxAmlRiskScore);
    }

    private Object fetch(SupportDownstreamContext context,
                         String service,
                         String section,
                         String path,
                         Map<String, ?> queryParams) {
        BackendRoute route = adminRoute(service);
        if (route == null) {
            context.errors().add(new SupportOverviewError(service, section, null, null, "admin route is not configured"));
            return null;
        }
        URI uri;
        try {
            uri = targetUri(route, path, queryParams);
        } catch (IllegalArgumentException ex) {
            context.errors().add(new SupportOverviewError(service, section, null, null, ex.getMessage()));
            return null;
        }
        try {
            ResponseEntity<Object> response = restTemplate.exchange(
                    uri, HttpMethod.GET, new HttpEntity<>(headers(context, route)), Object.class);
            return response.getBody();
        } catch (RestClientResponseException ex) {
            context.errors().add(new SupportOverviewError(
                    service, section, uri.toString(), ex.getStatusCode().value(), responseBodyOrMessage(ex)));
        } catch (ResourceAccessException ex) {
            context.errors().add(new SupportOverviewError(
                    service, section, uri.toString(), HttpStatus.GATEWAY_TIMEOUT.value(), "backend request timed out"));
        } catch (RestClientException | IllegalArgumentException ex) {
            context.errors().add(new SupportOverviewError(
                    service, section, uri.toString(), HttpStatus.BAD_GATEWAY.value(), ex.getMessage()));
        }
        return null;
    }

    private HttpHeaders headers(SupportDownstreamContext context, BackendRoute route) {
        HttpHeaders headers = new HttpHeaders();
        headers.setAccept(List.of(MediaType.APPLICATION_JSON));
        headers.set("X-Admin-User-Id", Long.toString(context.principal().userId()));
        headers.set("X-Admin-Username", context.principal().username());
        headers.set("X-Admin-Roles", String.join(",", context.principal().roles()));
        String requestId = context.request().getHeader("X-Request-Id");
        if (requestId != null && !requestId.isBlank()) {
            headers.set("X-Request-Id", requestId);
        }
        String forwardedFor = context.request().getHeader("X-Forwarded-For");
        if (forwardedFor != null && !forwardedFor.isBlank()) {
            headers.set("X-Forwarded-For", forwardedFor);
        }
        if (route.hasBasicAuth()) {
            headers.setBasicAuth(route.getBasicAuthUsername(), route.getBasicAuthPassword());
        }
        return headers;
    }

    private BackendRoute adminRoute(String service) {
        if (service == null) {
            return null;
        }
        return properties.getAdminRoutes().get(service.trim().toLowerCase(Locale.ROOT));
    }

    private URI targetUri(BackendRoute route, String path, Map<String, ?> queryParams) {
        UriComponentsBuilder builder = UriComponentsBuilder.fromUriString(
                trimTrailingSlash(route.getBaseUrl()) + ensureLeadingSlash(route.getTargetPrefix()) + normalizeSuffix(path));
        queryParams.forEach((key, value) -> {
            if (value != null && !String.valueOf(value).isBlank()) {
                builder.queryParam(key, value);
            }
        });
        return builder.build().toUri();
    }

    private int boundLimit(int limit) {
        if (limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be positive");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalizeAsset(String asset) {
        String normalized = asset == null || asset.isBlank() ? "USDT" : asset.trim().toUpperCase(Locale.ROOT);
        if (normalized.length() > 20) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "settleAsset is too long");
        }
        return normalized;
    }

    private String normalizeTicketStatus(String status) {
        String normalized = requireText(status, "status", 32).toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "PENDING_USER", "PENDING_INTERNAL", "RESOLVED", "CLOSED").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ticket status: " + status);
        }
        return normalized;
    }

    private String normalizePriority(String priority) {
        String normalized = priority == null || priority.isBlank() ? "MEDIUM" : priority.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid ticket priority: " + priority);
        }
        return normalized;
    }

    private String normalizeNoteType(String noteType) {
        String normalized = noteType == null || noteType.isBlank() ? "NOTE" : noteType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NOTE", "STATUS_CHANGE", "ESCALATION", "FOLLOW_UP").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid note type: " + noteType);
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "INTERNAL"
                : visibility.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INTERNAL", "CUSTOMER").contains(normalized)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid note visibility: " + visibility);
        }
        return normalized;
    }

    private String normalizeCode(String value, String fallback, String field) {
        String normalized = value == null || value.isBlank() ? fallback : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid " + field + ": " + value);
        }
        return normalized;
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    private String responseBodyOrMessage(RestClientResponseException ex) {
        String body = ex.getResponseBodyAsString();
        if (body != null && !body.isBlank()) {
            return body.length() <= 1000 ? body : body.substring(0, 1000);
        }
        return ex.getMessage();
    }

    private static String trimTrailingSlash(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("backend baseUrl is required");
        }
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private static String ensureLeadingSlash(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private static String normalizeSuffix(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.startsWith("/") ? value : "/" + value;
    }

    private record SupportDownstreamContext(
            JwtPrincipal principal,
            HttpServletRequest request,
            List<SupportOverviewError> errors) {
    }

    public record SupportUserOverviewResponse(
            Instant generatedAt,
            SupportUserSummary user,
            SupportComplianceSummary compliance,
            SupportAccountOverview account,
            SupportTradingOverview trading,
            SupportRiskOverview risk,
            List<SupportOverviewError> errors) {
    }

    public record SupportTicketQueryResponse(int ticketCount,
                                             List<SupportTicket> tickets,
                                             String nextCursor,
                                             boolean hasMore,
                                             String sort,
                                             int limit) {

        public SupportTicketQueryResponse(int ticketCount, List<SupportTicket> tickets) {
            this(ticketCount, tickets, null, false, null, ticketCount);
        }
    }

    public record SupportTicketDetailResponse(SupportTicket ticket,
                                              int noteCount,
                                              List<SupportTicketNote> notes) {
    }

    public record SupportTicketNotesResponse(int noteCount,
                                             List<SupportTicketNote> notes,
                                             String nextCursor,
                                             boolean hasMore,
                                             String sort,
                                             int limit) {
        public SupportTicketNotesResponse(int noteCount, List<SupportTicketNote> notes) {
            this(noteCount, notes, null, false, "createdAt.asc", noteCount);
        }
    }

    public record CreateSupportTicketRequest(String title,
                                             String category,
                                             String priority,
                                             Long assignedAdminUserId,
                                             String initialNote) {
    }

    public record SupportTicketNoteRequest(String noteType,
                                           String visibility,
                                           String body) {
    }

    public record SupportTicketStatusRequest(String status,
                                             String reason) {
    }

    public record SupportUserSummary(
            long userId,
            String username,
            String email,
            String status,
            Instant createdAt) {
    }

    public record SupportComplianceSummary(
            String kycLevel,
            String kycStatus,
            String country,
            Instant kycExpiresAt,
            int activeRiskTags,
            long criticalRiskTags,
            int openAmlCases,
            int maxAmlRiskScore) {
    }

    public record SupportAccountOverview(
            Object balances,
            Object productBalances,
            Object positions,
            Object transfers) {
    }

    public record SupportTradingOverview(
            Object orders,
            Object trades,
            Object triggerOrders) {
    }

    public record SupportRiskOverview(
            Object accountLatest,
            Object positionsLatest) {
    }

    public record SupportOverviewError(
            String service,
            String section,
            String targetUri,
            Integer httpStatus,
            String message) {
    }
}
