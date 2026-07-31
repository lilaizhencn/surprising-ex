package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthService;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicket;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicketNote;
import com.surprising.gateway.provider.auth.SupportTicketService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final AuthService authService;
    private final ComplianceService complianceService;
    private final SupportTicketService supportTicketService;

    public AdminSupportController(AuthService authService,
                                  ComplianceService complianceService,
                                  SupportTicketService supportTicketService) {
        this.authService = authService;
        this.complianceService = complianceService;
        this.supportTicketService = supportTicketService;
    }

    @GetMapping("/users/{userId}/overview")
    public SupportUserOverviewResponse overview(@RequestHeader("Authorization") String authorization,
                                                @PathVariable("userId") long userId) {
        supportPrincipal(authorization);
        AuthenticatedUser user = user(authorization, userId);
        return new SupportUserOverviewResponse(
                Instant.now(),
                new SupportUserSummary(user.userId(), user.username(), user.email(), user.status(), user.createdAt()),
                compliance(userId));
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
        var page = supportTicketService.ticketsPage(
                userId, normalizedStatus, boundLimit(limit), cursor, sort);
        return new SupportTicketQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    @PostMapping("/users/{userId}/tickets")
    public SupportTicketDetailResponse createTicket(@RequestHeader("Authorization") String authorization,
                                                    @PathVariable("userId") long userId,
                                                    @RequestBody CreateSupportTicketRequest request) {
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        user(authorization, userId);
        CreateSupportTicketRequest body = request == null
                ? new CreateSupportTicketRequest(null, null, null, null, null)
                : request;
        Instant now = Instant.now();
        String initialNote = body.initialNote() == null || body.initialNote().isBlank()
                ? null
                : requireText(body.initialNote(), "initialNote", 2000);
        var mutation = supportTicketService.createTicket(
                userId, normalizePriority(body.priority()), normalizeCode(body.category(), "GENERAL", "category"),
                requireText(body.title(), "title", 160), body.assignedAdminUserId(), principal.userId(),
                initialNote, now);
        return new SupportTicketDetailResponse(
                mutation.ticket(), mutation.notes().size(), mutation.notes());
    }

    @GetMapping("/tickets/{ticketId}/notes")
    public SupportTicketNotesResponse notes(@RequestHeader("Authorization") String authorization,
                                            @PathVariable("ticketId") long ticketId,
                                            @RequestParam(value = "limit", defaultValue = "200") int limit,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "sort", required = false) String sort) {
        supportPrincipal(authorization);
        var page = supportTicketService.notesPage(ticketId, Math.min(Math.max(limit, 1), 500), cursor, sort);
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
            return supportTicketService.addNote(ticketId, principal.userId(), normalizeNoteType(body.noteType()),
                    normalizeVisibility(body.visibility()), requireText(body.body(), "body", 2000), Instant.now());
        } catch (EmptyResultDataAccessException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "support ticket not found", ex);
        }
    }

    @PostMapping("/tickets/{ticketId}/status")
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
            var mutation = supportTicketService.updateStatus(
                    ticketId, status, principal.userId(), reason, now);
            return new SupportTicketDetailResponse(
                    mutation.ticket(), mutation.notes().size(), mutation.notes());
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
        KycProfile kyc = complianceService.kyc(userId);
        List<RiskTag> activeTags = complianceService.riskTags(userId, "ACTIVE", 100);
        List<AmlCase> openCases = complianceService.amlCases(userId, null, 100).stream()
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

    private int boundLimit(int limit) {
        if (limit < 1) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be positive");
        }
        return Math.min(limit, MAX_LIMIT);
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

    public record SupportUserOverviewResponse(
            Instant generatedAt,
            SupportUserSummary user,
            SupportComplianceSummary compliance) {
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

}
