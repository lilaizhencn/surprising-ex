package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.SupportTicketRepository.CursorPage;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicket;
import com.surprising.gateway.provider.auth.SupportTicketRepository.SupportTicketNote;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在服务层聚合工单与工单备注的单表仓储。
 */
@Service
public class SupportTicketService {

    private final SupportTicketRepository ticketRepository;
    private final SupportTicketNoteRepository noteRepository;

    public SupportTicketService(SupportTicketRepository ticketRepository,
                                SupportTicketNoteRepository noteRepository) {
        this.ticketRepository = ticketRepository;
        this.noteRepository = noteRepository;
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
}
