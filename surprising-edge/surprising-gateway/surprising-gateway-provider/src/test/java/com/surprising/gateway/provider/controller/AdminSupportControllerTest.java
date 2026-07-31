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
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.SupportTicketRepository;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicket;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicketNote;
import com.surprising.gateway.provider.auth.SupportTicketService;
import com.surprising.gateway.provider.auth.SupportTicketService.TicketMutation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminSupportControllerTest {

    @Test
    void overviewRequiresSupportPermissionAndReturnsReadOnlyUserContext() {
        AuthService authService = mock(AuthService.class);
        ComplianceService complianceService = mock(ComplianceService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        when(authService.adminUser("Bearer support", 1001L))
                .thenReturn(new AuthenticatedUser(1001L, "alice", "alice@example.com", "NORMAL",
                        List.of("USER"), now));
        when(complianceService.kyc(1001L)).thenReturn(new KycProfile(
                1001L, "STANDARD", "VERIFIED", "SG", "PASSPORT", "manual", "case-1",
                7L, now, null, now.plusSeconds(86400), now, now));
        when(complianceService.riskTags(1001L, "ACTIVE", 100)).thenReturn(List.of(
                new RiskTag(1L, 1001L, "AML_REVIEW", "CRITICAL", "ACTIVE", "manual",
                        "review", 7L, null, now, null, now)));
        when(complianceService.amlCases(1001L, null, 100)).thenReturn(List.of(
                new AmlCase(2L, 1001L, "REVIEWING", 75, "manual", "case",
                        null, 7L, null, null, null, now, now)));
        AdminSupportController controller = new AdminSupportController(
                authService, complianceService, mock(SupportTicketService.class));

        var response = controller.overview("Bearer support", 1001L);

        assertThat(response.user().userId()).isEqualTo(1001L);
        assertThat(response.user().status()).isEqualTo("NORMAL");
        assertThat(response.compliance().kycStatus()).isEqualTo("VERIFIED");
        assertThat(response.compliance().criticalRiskTags()).isEqualTo(1);
        assertThat(response.compliance().openAmlCases()).isEqualTo(1);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
    }

    @Test
    void supportCanCreateTicketWithInitialInternalNote() {
        AuthService authService = mock(AuthService.class);
        ComplianceService complianceService = mock(ComplianceService.class);
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
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
        when(supportTicketService.createTicket(eq(1001L), eq("HIGH"), eq("WITHDRAWAL"),
                eq("Withdrawal delayed"), eq(9L), eq(9L), eq("checking wallet tx"), any(Instant.class)))
                .thenReturn(new TicketMutation(ticket, List.of(note)));
        AdminSupportController controller = new AdminSupportController(
                authService, complianceService, supportTicketService);

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
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        SupportTicket ticket = new SupportTicket(501L, 1001L, "OPEN", "HIGH", "WITHDRAWAL",
                "Withdrawal delayed", 9L, 9L, null, now, now, null);
        when(supportTicketService.ticketsPage(1001L, "OPEN", 50, "cursor", "updatedAt.asc"))
                .thenReturn(new SupportTicketRepository.CursorPage<>(List.of(ticket), "next", true,
                        "updatedAt.asc", 50));
        AdminSupportController controller = new AdminSupportController(
                authService, mock(ComplianceService.class), supportTicketService);

        var response = controller.tickets("Bearer support", 1001L, "OPEN", 50,
                "cursor", "updatedAt.asc");

        assertThat(response.tickets()).containsExactly(ticket);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
        verify(supportTicketService).ticketsPage(1001L, "OPEN", 50, "cursor", "updatedAt.asc");
    }

    @Test
    void notesReturnCursorPage() {
        AuthService authService = mock(AuthService.class);
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(authService.requireAdminPermission("Bearer support", "admin.support.read"))
                .thenReturn(new JwtPrincipal(9L, "support", "NORMAL", List.of("SUPPORT"), now.plusSeconds(300)));
        SupportTicketNote note = new SupportTicketNote(701L, 501L, 9L, "NOTE", "INTERNAL",
                "checking wallet tx", now);
        when(supportTicketService.notesPage(501L, 25, "cursor", "createdAt.desc"))
                .thenReturn(new SupportTicketRepository.CursorPage<>(List.of(note), "next", true,
                        "createdAt.desc", 25));
        AdminSupportController controller = new AdminSupportController(
                authService, mock(ComplianceService.class), supportTicketService);

        var response = controller.notes("Bearer support", 501L, 25, "cursor", "createdAt.desc");

        assertThat(response.notes()).containsExactly(note);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.desc");
        assertThat(response.limit()).isEqualTo(25);
        verify(authService).requireAdminPermission("Bearer support", "admin.support.read");
        verify(supportTicketService).notesPage(501L, 25, "cursor", "createdAt.desc");
    }

}
