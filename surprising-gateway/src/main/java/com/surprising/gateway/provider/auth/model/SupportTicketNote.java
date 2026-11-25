package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record SupportTicketNote(
        long noteId,
        long ticketId,
        long adminUserId,
        String noteType,
        String visibility,
        String body,
        Instant createdAt) {
}
