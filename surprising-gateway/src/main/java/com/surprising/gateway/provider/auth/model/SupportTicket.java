package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record SupportTicket(
        long ticketId,
        long userId,
        String status,
        String priority,
        String category,
        String title,
        Long assignedAdminUserId,
        long createdByUserId,
        Long resolvedByUserId,
        Instant createdAt,
        Instant updatedAt,
        Instant closedAt) {
}
