package com.surprising.gateway.provider.auth.model;

import java.time.Instant;

public record KycDocument(
        long documentId,
        long userId,
        String documentType,
        String originalFilename,
        String contentType,
        long fileSize,
        String sha256,
        String status,
        Instant createdAt,
        Instant deletedAt) {
}
