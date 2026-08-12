package com.surprising.gateway.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.gateway.provider.auth.SupportModels.CursorPage;
import com.surprising.gateway.provider.auth.SupportModels.SupportComplianceSummary;
import com.surprising.gateway.provider.auth.SupportModels.SupportOverview;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicket;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicketNote;
import com.surprising.gateway.provider.auth.SupportModels.SupportUserSummary;
import com.surprising.gateway.provider.auth.SupportTicketService;
import com.surprising.gateway.provider.auth.SupportTicketService.TicketMutation;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class AdminSupportControllerTest {

    @Test
    void overviewRequiresSupportPermissionAndReturnsReadOnlyUserContext() {
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        when(supportTicketService.adminOverview("Bearer support", 1001L))
                .thenReturn(new SupportOverview(
                        now,
                        new SupportUserSummary(
                                1001L, "alice", "alice@example.com", "NORMAL", now),
                        new SupportComplianceSummary(
                                "STANDARD", "VERIFIED", "SG", now.plusSeconds(86400),
                                1, 1, 1, 75)));
        AdminSupportController controller = new AdminSupportController(supportTicketService);

        var response = controller.overview("Bearer support", 1001L);

        assertThat(response.user().userId()).isEqualTo(1001L);
        assertThat(response.user().status()).isEqualTo("NORMAL");
        assertThat(response.compliance().kycStatus()).isEqualTo("VERIFIED");
        assertThat(response.compliance().criticalRiskTags()).isEqualTo(1);
        assertThat(response.compliance().openAmlCases()).isEqualTo(1);
        verify(supportTicketService).adminOverview("Bearer support", 1001L);
    }

    @Test
    void supportCanCreateTicketWithInitialInternalNote() {
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        SupportTicket ticket = new SupportTicket(501L, 1001L, "OPEN", "HIGH", "WITHDRAWAL",
                "Withdrawal delayed", 9L, 9L, null, now, now, null);
        SupportTicketNote note = new SupportTicketNote(701L, 501L, 9L, "NOTE", "INTERNAL",
                "checking wallet tx", now);
        when(supportTicketService.adminCreateTicket(
                "Bearer support", 1001L, "Withdrawal delayed", "withdrawal",
                "high", 9L, "checking wallet tx"))
                .thenReturn(new TicketMutation(ticket, List.of(note)));
        AdminSupportController controller = new AdminSupportController(supportTicketService);

        var response = controller.createTicket("Bearer support", 1001L,
                new AdminSupportController.CreateSupportTicketRequest(
                        "Withdrawal delayed", "withdrawal", "high", 9L, "checking wallet tx"));

        assertThat(response.ticket().ticketId()).isEqualTo(501L);
        assertThat(response.notes()).containsExactly(note);
        verify(supportTicketService).adminCreateTicket(
                "Bearer support", 1001L, "Withdrawal delayed", "withdrawal",
                "high", 9L, "checking wallet tx");
    }

    @Test
    void ticketsReturnCursorPage() {
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        SupportTicket ticket = new SupportTicket(501L, 1001L, "OPEN", "HIGH", "WITHDRAWAL",
                "Withdrawal delayed", 9L, 9L, null, now, now, null);
        when(supportTicketService.adminTicketsPage(
                "Bearer support", 1001L, "OPEN", 50, "cursor", "updatedAt.asc"))
                .thenReturn(new CursorPage<>(List.of(ticket), "next", true,
                        "updatedAt.asc", 50));
        AdminSupportController controller = new AdminSupportController(supportTicketService);

        var response = controller.tickets("Bearer support", 1001L, "OPEN", 50,
                "cursor", "updatedAt.asc");

        assertThat(response.tickets()).containsExactly(ticket);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("updatedAt.asc");
        assertThat(response.limit()).isEqualTo(50);
        verify(supportTicketService).adminTicketsPage(
                "Bearer support", 1001L, "OPEN", 50, "cursor", "updatedAt.asc");
    }

    @Test
    void notesReturnCursorPage() {
        SupportTicketService supportTicketService = mock(SupportTicketService.class);
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        SupportTicketNote note = new SupportTicketNote(701L, 501L, 9L, "NOTE", "INTERNAL",
                "checking wallet tx", now);
        when(supportTicketService.adminNotesPage(
                "Bearer support", 501L, 25, "cursor", "createdAt.desc"))
                .thenReturn(new CursorPage<>(List.of(note), "next", true,
                        "createdAt.desc", 25));
        AdminSupportController controller = new AdminSupportController(supportTicketService);

        var response = controller.notes("Bearer support", 501L, 25, "cursor", "createdAt.desc");

        assertThat(response.notes()).containsExactly(note);
        assertThat(response.nextCursor()).isEqualTo("next");
        assertThat(response.hasMore()).isTrue();
        assertThat(response.sort()).isEqualTo("createdAt.desc");
        assertThat(response.limit()).isEqualTo(25);
        verify(supportTicketService).adminNotesPage(
                "Bearer support", 501L, 25, "cursor", "createdAt.desc");
    }

}
