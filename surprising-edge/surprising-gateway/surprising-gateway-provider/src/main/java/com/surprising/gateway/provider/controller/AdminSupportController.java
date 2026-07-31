package com.surprising.gateway.provider.controller;

import com.surprising.gateway.provider.auth.SupportModels.SupportComplianceSummary;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicket;
import com.surprising.gateway.provider.auth.SupportModels.SupportTicketNote;
import com.surprising.gateway.provider.auth.SupportModels.SupportUserSummary;
import com.surprising.gateway.provider.auth.SupportTicketService;
import java.time.Instant;
import java.util.List;
import java.util.function.Supplier;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * 后台客服 HTTP 入口，只负责请求参数接收、响应映射和异常状态转换。
 */
@RestController
@RequestMapping("/api/v1/admin/support")
public class AdminSupportController {

    private static final int DEFAULT_LIMIT = 25;

    private final SupportTicketService supportTicketService;

    public AdminSupportController(SupportTicketService supportTicketService) {
        this.supportTicketService = supportTicketService;
    }

    @GetMapping("/users/{userId}/overview")
    public SupportUserOverviewResponse overview(@RequestHeader("Authorization") String authorization,
                                                @PathVariable("userId") long userId) {
        return execute(() -> {
            var overview = supportTicketService.adminOverview(authorization, userId);
            return new SupportUserOverviewResponse(
                    overview.generatedAt(), overview.user(), overview.compliance());
        });
    }

    @GetMapping("/tickets")
    public SupportTicketQueryResponse tickets(@RequestHeader("Authorization") String authorization,
                                              @RequestParam(value = "userId", required = false) Long userId,
                                              @RequestParam(value = "status", required = false) String status,
                                              @RequestParam(value = "limit", defaultValue = "" + DEFAULT_LIMIT) int limit,
                                              @RequestParam(value = "cursor", required = false) String cursor,
                                              @RequestParam(value = "sort", required = false) String sort) {
        return execute(() -> {
            var page = supportTicketService.adminTicketsPage(
                    authorization, userId, status, limit, cursor, sort);
            return new SupportTicketQueryResponse(
                    page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        });
    }

    @PostMapping("/users/{userId}/tickets")
    public SupportTicketDetailResponse createTicket(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("userId") long userId,
            @RequestBody(required = false) CreateSupportTicketRequest request) {
        CreateSupportTicketRequest body = request == null
                ? new CreateSupportTicketRequest(null, null, null, null, null)
                : request;
        return execute(() -> {
            var mutation = supportTicketService.adminCreateTicket(
                    authorization,
                    userId,
                    body.title(),
                    body.category(),
                    body.priority(),
                    body.assignedAdminUserId(),
                    body.initialNote());
            return new SupportTicketDetailResponse(
                    mutation.ticket(), mutation.notes().size(), mutation.notes());
        });
    }

    @GetMapping("/tickets/{ticketId}/notes")
    public SupportTicketNotesResponse notes(@RequestHeader("Authorization") String authorization,
                                            @PathVariable("ticketId") long ticketId,
                                            @RequestParam(value = "limit", defaultValue = "200") int limit,
                                            @RequestParam(value = "cursor", required = false) String cursor,
                                            @RequestParam(value = "sort", required = false) String sort) {
        return execute(() -> {
            var page = supportTicketService.adminNotesPage(
                    authorization, ticketId, limit, cursor, sort);
            return new SupportTicketNotesResponse(
                    page.items().size(), page.items(), page.nextCursor(),
                    page.hasMore(), page.sort(), page.limit());
        });
    }

    @PostMapping("/tickets/{ticketId}/notes")
    public SupportTicketNote addNote(@RequestHeader("Authorization") String authorization,
                                     @PathVariable("ticketId") long ticketId,
                                     @RequestBody(required = false) SupportTicketNoteRequest request) {
        SupportTicketNoteRequest body = request == null
                ? new SupportTicketNoteRequest(null, null, null)
                : request;
        return execute(() -> supportTicketService.adminAddNote(
                authorization, ticketId, body.noteType(), body.visibility(), body.body()));
    }

    @PostMapping("/tickets/{ticketId}/status")
    public SupportTicketDetailResponse updateStatus(
            @RequestHeader("Authorization") String authorization,
            @PathVariable("ticketId") long ticketId,
            @RequestBody(required = false) SupportTicketStatusRequest request) {
        SupportTicketStatusRequest body = request == null
                ? new SupportTicketStatusRequest(null, null)
                : request;
        return execute(() -> {
            var mutation = supportTicketService.adminUpdateStatus(
                    authorization, ticketId, body.status(), body.reason());
            return new SupportTicketDetailResponse(
                    mutation.ticket(), mutation.notes().size(), mutation.notes());
        });
    }

    private <T> T execute(Supplier<T> action) {
        try {
            return action.get();
        } catch (SupportTicketService.SupportUnauthorizedException ex) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, ex.getMessage(), ex);
        } catch (SupportTicketService.SupportForbiddenException ex) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, ex.getMessage(), ex);
        } catch (SupportTicketService.SupportTicketNotFoundException ex) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, ex.getMessage(), ex);
        } catch (IllegalArgumentException ex) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, ex.getMessage(), ex);
        }
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
}
