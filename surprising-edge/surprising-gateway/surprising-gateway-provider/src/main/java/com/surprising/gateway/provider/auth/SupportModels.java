package com.surprising.gateway.provider.auth;

import java.time.Instant;
import java.util.List;

/**
 * 客服工单领域模型，避免协议层依赖持久化实现类型。
 */
public final class SupportModels {

    private SupportModels() {
    }

    public record SupportTicket(long ticketId,
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

    public record SupportTicketNote(long noteId,
                                    long ticketId,
                                    long adminUserId,
                                    String noteType,
                                    String visibility,
                                    String body,
                                    Instant createdAt) {
    }

    public record CursorPage<T>(
            List<T> items,
            String nextCursor,
            boolean hasMore,
            String sort,
            int limit) {
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

    public record SupportOverview(
            Instant generatedAt,
            SupportUserSummary user,
            SupportComplianceSummary compliance) {
    }
}
