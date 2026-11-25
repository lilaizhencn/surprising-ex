package com.surprising.gateway.provider.auth.service;


import com.surprising.gateway.provider.auth.model.AmlCase;
import com.surprising.gateway.provider.auth.model.AuthenticatedUser;
import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.KycProfile;
import com.surprising.gateway.provider.auth.model.RiskTag;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.TicketMutation;
import com.surprising.gateway.provider.exception.SupportUnauthorizedException;
import com.surprising.gateway.provider.exception.SupportForbiddenException;
import com.surprising.gateway.provider.exception.SupportTicketNotFoundException;
import com.surprising.gateway.provider.auth.repository.SupportTicketNoteRepository;
import com.surprising.gateway.provider.auth.repository.SupportTicketRepository;

import com.surprising.gateway.provider.auth.model.CursorPage;
import com.surprising.gateway.provider.auth.model.SupportTicket;
import com.surprising.gateway.provider.auth.model.SupportTicketNote;
import com.surprising.gateway.provider.auth.model.SupportComplianceSummary;
import com.surprising.gateway.provider.auth.model.SupportOverview;
import com.surprising.gateway.provider.auth.model.SupportUserSummary;
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
    private final AdminAuthService adminAuthService;
    private final ComplianceService complianceService;

    @org.springframework.beans.factory.annotation.Autowired
    public SupportTicketService(SupportTicketRepository ticketRepository,
                                SupportTicketNoteRepository noteRepository,
                                AdminAuthService adminAuthService,
                                ComplianceService complianceService) {
        this.ticketRepository = ticketRepository;
        this.noteRepository = noteRepository;
        this.adminAuthService = adminAuthService;
        this.complianceService = complianceService;
    }

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                SupportTicketNoteRepository noteRepository,
                                AuthService authService,
                                ComplianceService complianceService) {
        this(ticketRepository, noteRepository,
                new AdminAuthService(authService.properties(), authService.persistence(), authService),
                complianceService);
    }

    public SupportOverview adminOverview(String authorization, long userId) {
        supportPrincipal(authorization, "admin.support.read");
        AuthenticatedUser user = adminUser(authorization, userId);
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
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
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
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
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
        JwtPrincipal principal = supportPrincipal(authorization, "admin.support.write");
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

    private JwtPrincipal supportPrincipal(String authorization, String permission) {
        try {
            return adminAuthService.requireAdminPermission(authorization, permission);
        } catch (IllegalArgumentException ex) {
            throw new SupportUnauthorizedException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new SupportForbiddenException(ex.getMessage(), ex);
        }
    }

    private AuthenticatedUser adminUser(String authorization, long userId) {
        try {
            return adminAuthService.adminUser(authorization, userId);
        } catch (IllegalArgumentException ex) {
            throw new SupportTicketNotFoundException(ex.getMessage(), ex);
        } catch (IllegalStateException ex) {
            throw new SupportForbiddenException(ex.getMessage(), ex);
        }
    }

    private SupportComplianceSummary compliance(long userId) {
        KycProfile kyc = complianceService.kyc(userId);
        List<RiskTag> activeTags = complianceService.riskTags(userId, "ACTIVE", 100);
        List<AmlCase> openCases = complianceService.amlCases(userId, null, 100).stream()
                .filter(item -> !"CLOSED".equals(item.status()) && !"CLEARED".equals(item.status()))
                .toList();
        long criticalTags = activeTags.stream()
                .filter(item -> "CRITICAL".equals(item.severity()))
                .count();
        int maxAmlRiskScore = openCases.stream()
                .mapToInt(AmlCase::riskScore)
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
