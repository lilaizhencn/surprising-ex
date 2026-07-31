package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.SupportModels.CursorPage;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicket;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicketNote;
import com.surprising.gateway.provider.auth.SupportModels.SupportComplianceSummary;
import com.surprising.gateway.provider.auth.SupportModels.SupportOverview;
import com.surprising.gateway.provider.auth.SupportModels.SupportUserSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在服务层聚合工单与工单备注的单表仓储。
 */
@Service
public class SupportTicketService {

    private static final int DEFAULT_LIMIT = 25;
    private static final int MAX_LIMIT = 100;

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketNoteRepository noteRepository;
    private final AuthService authService;
    private final ComplianceService complianceService;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                SupportTicketNoteRepository noteRepository,
                                AuthService authService,
                                ComplianceService complianceService) {
        this.ticketRepository = ticketRepository;
        this.noteRepository = noteRepository;
        this.authService = authService;
        this.complianceService = complianceService;
    }

    public SupportOverview adminOverview(String authorization, long userId) {
        supportPrincipal(authorization, "admin.support.read");
        AuthModels.AuthenticatedUser user = adminUser(authorization, userId);
        return new SupportOverview(
                Instant.now(),
                new SupportUserSummary(
                        user.userId(), user.username(), user.email(), user.status(), user.createdAt()),
                compliance(userId));
    }

    public CursorPage<SupportTicket> adminTicketsPage(String authorization,
                                                       Long userId,
                                                       String status,
                                                       int limit,
                                                       String cursor,
                                                       String sort) {
        supportPrincipal(authorization, "admin.support.read");
        String normalizedStatus = status == null || status.isBlank() ? null : normalizeTicketStatus(status);
        return ticketsPage(userId, normalizedStatus, boundLimit(limit), cursor, sort);
    }

    public TicketMutation adminCreateTicket(String authorization,
                                            long userId,
                                            String title,
                                            String category,
                                            String priority,
                                            Long assignedAdminUserId,
                                            String initialNote) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        adminUser(authorization, userId);
        String normalizedInitialNote = initialNote == null || initialNote.isBlank()
                ? null
                : requireText(initialNote, "initialNote", 2000);
        return createTicket(
                userId,
                normalizePriority(priority),
                normalizeCode(category, "GENERAL", "category"),
                requireText(title, "title", 160),
                assignedAdminUserId,
                principal.userId(),
                normalizedInitialNote,
                Instant.now());
    }

    public CursorPage<SupportTicketNote> adminNotesPage(String authorization,
                                                        long ticketId,
                                                        int limit,
                                                        String cursor,
                                                        String sort) {
        supportPrincipal(authorization, "admin.support.read");
        return notesPage(ticketId, Math.min(Math.max(limit, 1), 500), cursor, sort);
    }

    public SupportTicketNote adminAddNote(String authorization,
                                          long ticketId,
                                          String noteType,
                                          String visibility,
                                          String body) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        try {
            return addNote(
                    ticketId,
                    principal.userId(),
                    normalizeNoteType(noteType),
                    normalizeVisibility(visibility),
                    requireText(body, "body", 2000),
                    Instant.now());
        } catch (EmptyResultDataAccessException ex) {
            throw new SupportTicketNotFoundException("support ticket not found", ex);
        }
    }

    public TicketMutation adminUpdateStatus(String authorization,
                                            long ticketId,
                                            String status,
                                            String reason) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        try {
            return updateStatus(
                    ticketId,
                    normalizeTicketStatus(status),
                    principal.userId(),
                    requireText(reason, "reason", 2000),
                    Instant.now());
        } catch (EmptyResultDataAccessException ex) {
            throw new SupportTicketNotFoundException("support ticket not found", ex);
        }
    }

    private AuthModels.JwtPrincipal supportPrincipal(String authorization, String permission) {
        try {
            return authService.requireAdminPermission(authorization, permission);
        } catch (IllegalArgumentException ex) {
            throw new SupportUnauthorizedException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new SupportForbiddenException(ex.getMessage(), ex);
        }
    }

    private AuthModels.AuthenticatedUser adminUser(String authorization, long userId) {
        try {
            return authService.adminUser(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new SupportTicketNotFoundException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new SupportForbiddenException(ex.getMessage(), ex);
        }
    }

    private SupportComplianceSummary compliance(long userId) {
        ComplianceModels.KycProfile kyc = complianceService.kyc(userId);
        List<ComplianceModels.RiskTag> activeTags = complianceService.riskTags(userId, "ACTIVE", 100);
        List<ComplianceModels.AmlCase> openCases = complianceService.amlCases(userId, null, 100).stream()
                .filter(item -> !"CLOSED".equals(item.status()) && !"CLEARED".equals(item.status()))
                .toList();
        long criticalTags = activeTags.stream()
                .filter(item -> "CRITICAL".equals(item.severity()))
                .count();
        int maxAmlRiskScore = openCases.stream()
                .mapToInt(ComplianceModels.AmlCase::riskScore)
                .max()
                .orElse(0);
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
            throw new IllegalArgumentException("limit must be positive");
        }
        return Math.min(limit, MAX_LIMIT);
    }

    private String normalizeTicketStatus(String status) {
        String normalized = requireText(status, "status", 32).toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "PENDING_USER", "PENDING_INTERNAL", "RESOLVED", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("invalid ticket status: " + status);
        }
        return normalized;
    }

    private String normalizePriority(String priority) {
        String normalized = priority == null || priority.isBlank()
                ? "MEDIUM"
                : priority.trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "URGENT").contains(normalized)) {
            throw new IllegalArgumentException("invalid ticket priority: " + priority);
        }
        return normalized;
    }

    private String normalizeNoteType(String noteType) {
        String normalized = noteType == null || noteType.isBlank()
                ? "NOTE"
                : noteType.trim().toUpperCase(Locale.ROOT);
        if (!List.of("NOTE", "STATUS_CHANGE", "ESCALATION", "FOLLOW_UP").contains(normalized)) {
            throw new IllegalArgumentException("invalid note type: " + noteType);
        }
        return normalized;
    }

    private String normalizeVisibility(String visibility) {
        String normalized = visibility == null || visibility.isBlank()
                ? "INTERNAL"
                : visibility.trim().toUpperCase(Locale.ROOT);
        if (!List.of("INTERNAL", "CUSTOMER").contains(normalized)) {
            throw new IllegalArgumentException("invalid note visibility: " + visibility);
        }
        return normalized;
    }

    private String normalizeCode(String value, String fallback, String field) {
        String normalized = value == null || value.isBlank()
                ? fallback
                : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
        return normalized;
    }

    private String requireText(String value, String field, int maxLength) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        String normalized = value.trim();
        if (normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " must be at most " + maxLength + " characters");
        }
        return normalized;
    }

    public CursorPage<SupportTicket> ticketsPage(Long userId,
                                                 String status,
                                                 int limit,
                                                 String cursor,
                                                 String sort) {
        return ticketRepository.ticketsPage(userId, status, limit, cursor, sort);
    }

    @Transactional
    public TicketMutation createTicket(long userId,
                                       String priority,
                                       String category,
                                       String title,
                                       Long assignedAdminUserId,
                                       long createdByUserId,
                                       String initialNote,
                                       Instant now) {
        SupportTicket ticket = ticketRepository.createTicket(
                userId, priority, category, title, assignedAdminUserId, createdByUserId, now);
        List<SupportTicketNote> notes = new ArrayList<>();
        if (initialNote != null) {
            notes.add(noteRepository.addNote(
                    ticket.ticketId(), createdByUserId, "NOTE", "INTERNAL", initialNote, now));
        }
        return new TicketMutation(ticket, List.copyOf(notes));
    }

    public CursorPage<SupportTicketNote> notesPage(long ticketId,
                                                   int limit,
                                                   String cursor,
                                                   String sort) {
        return noteRepository.notesPage(ticketId, limit, cursor, sort);
    }

    public SupportTicketNote addNote(long ticketId,
                                     long adminUserId,
                                     String noteType,
                                     String visibility,
                                     String body,
                                     Instant now) {
        return noteRepository.addNote(ticketId, adminUserId, noteType, visibility, body, now);
    }

    @Transactional
    public TicketMutation updateStatus(long ticketId,
                                       String status,
                                       long adminUserId,
                                       String reason,
                                       Instant now) {
        SupportTicket ticket = ticketRepository.updateStatus(ticketId, status, adminUserId, now);
        SupportTicketNote note = noteRepository.addNote(
                ticketId, adminUserId, "STATUS_CHANGE", "INTERNAL", reason, now);
        return new TicketMutation(ticket, List.of(note));
    }

    public record TicketMutation(
            SupportTicket ticket,
            List<SupportTicketNote> notes) {
    }

    public static class SupportUnauthorizedException extends RuntimeException {
        public SupportUnauthorizedException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SupportForbiddenException extends RuntimeException {
        public SupportForbiddenException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    public static class SupportTicketNotFoundException extends RuntimeException {
        public SupportTicketNotFoundException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
