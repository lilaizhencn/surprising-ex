package com.surprising.gateway.provider.content.model;

import java.time.Instant;
import java.util.UUID;

public record ContentMedia(UUID mediaId, String objectKey, String contentType, long size,
                           long uploadedBy, Instant createdAt) {
    public record Upload(UUID mediaId, String url, String contentType, long size) { }
}
