package com.surprising.gateway.provider.announcement;

import java.time.Instant;
import java.util.List;

public final class AnnouncementModels {

    private AnnouncementModels() {
    }

    public record TranslationInput(String locale, String title, String summary, String body) {
    }

    public record SaveRequest(String category,
                              Integer priority,
                              List<String> placements,
                              List<String> productLines,
                              List<String> platforms,
                              Instant startsAt,
                              Instant expiresAt,
                              List<TranslationInput> translations,
                              Long version) {
    }

    public record WithdrawRequest(String reason, Long version) {
    }

    public record PublishRequest(String reason, Long version) {
    }

    public record Translation(String locale, String title, String summary, String body) {
    }

    public record Announcement(long announcementId,
                               String category,
                               String status,
                               int priority,
                               List<String> placements,
                               List<String> productLines,
                               List<String> platforms,
                               Instant startsAt,
                               Instant expiresAt,
                               long version,
                               List<Translation> translations,
                               String locale,
                               String title,
                               String summary,
                               String body,
                               Instant readAt,
                               Instant createdAt,
                               Instant updatedAt,
                               String effectiveStatus) {
    }

    public record AdminPage(int count, List<Announcement> announcements, int offset, int limit, boolean hasMore) {
    }

    public record ClientPage(List<Announcement> announcements, int unreadCount, int offset, int limit,
                             boolean hasMore) {
    }

    public record AuditEntry(long auditId, long announcementId, String action, long adminUserId,
                             String adminUsername, String reason, String snapshot, Instant createdAt) {
    }
}
