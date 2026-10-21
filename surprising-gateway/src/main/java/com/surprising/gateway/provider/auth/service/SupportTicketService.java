package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.SupportModels.TicketMutation;
import com.surprising.gateway.provider.exception.SupportUnauthorizedException;
import com.surprising.gateway.provider.exception.SupportForbiddenException;
import com.surprising.gateway.provider.exception.SupportTicketNotFoundException;
import com.surprising.gateway.provider.auth.model.ComplianceModels;
import com.surprising.gateway.provider.auth.repository.SupportTicketNoteRepository;
import com.surprising.gateway.provider.auth.repository.SupportTicketRepository;

import com.surprising.gateway.provider.auth.model.SupportModels.CursorPage;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportTicket;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportTicketNote;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportComplianceSummary;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportOverview;
import com.surprising.gateway.provider.auth.model.SupportModels.SupportUserSummary;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在服务层聚合工单与工单备注的单表仓储。
 */
@Service
public class SupportTicketService {

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
                                                       AuthPageQuery pageQuery) {
        supportPrincipal(authorization, "admin.support.read");
        return ticketRepository.ticketsPage(userId, status, pageQuery);
    }

    @Transactional
    public TicketMutation adminCreateTicket(String authorization,
                                            long userId,
                                            String title,
                                            String category,
                                            String priority,
                                            Long assignedAdminUserId,
                                            String initialNote) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        adminUser(authorization, userId);
        return createTicket(
                userId,
                priority,
                category,
                title,
                assignedAdminUserId,
                principal.userId(),
                initialNote,
                Instant.now());
    }

    public CursorPage<SupportTicketNote> adminNotesPage(String authorization,
                                                        long ticketId,
                                                        AuthPageQuery pageQuery) {
        supportPrincipal(authorization, "admin.support.read");
        return noteRepository.notesPage(ticketId, pageQuery);
    }

    public SupportTicketNote adminAddNote(String authorization,
                                          long ticketId,
                                          String noteType,
                                          String visibility,
                                          String body) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        try {
            return noteRepository.addNote(
                    ticketId,
                    principal.userId(),
                    noteType,
                    visibility,
                    body,
                    Instant.now());
        } catch (EmptyResultDataAccessException ex) {
            throw new SupportTicketNotFoundException("support ticket not found", ex);
        }
    }

    @Transactional
    public TicketMutation adminUpdateStatus(String authorization,
                                            long ticketId,
                                            String status,
                                            String reason) {
        AuthModels.JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
        try {
            return updateStatus(
                    ticketId,
                    status,
                    principal.userId(),
                    reason,
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

    @Transactional
    public TicketMutation updateStatus(long ticketId,
                                       String status,
                                       long adminUserId,
                                       String reason,
                                       Instant now) {
        Long resolvedByUserId = List.of("RESOLVED", "CLOSED").contains(status) ? adminUserId : null;
        Instant closedAt = "CLOSED".equals(status) ? now : null;
        SupportTicket ticket = ticketRepository.updateStatus(ticketId, status, resolvedByUserId, closedAt, now);
        SupportTicketNote note = noteRepository.addNote(
                ticketId, adminUserId, "STATUS_CHANGE", "INTERNAL", reason, now);
        return new TicketMutation(ticket, List.of(note));
    }

}
