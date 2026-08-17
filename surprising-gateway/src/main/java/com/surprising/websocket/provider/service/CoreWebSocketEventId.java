package com.surprising.websocket.provider.service;

import com.surprising.product.api.ProductLine;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;

public final class CoreWebSocketEventId {

    public enum EventKind {
        CORE_EXPORT,
        ORDER,
        EXECUTION_REPORT,
        POSITION,
        TRIGGER_ORDER
    }

    private CoreWebSocketEventId() {
    }

    public static String of(ProductLine productLine, long exportSequence, EventKind eventKind,
                            String entityUserDiscriminator, int itemIndex) {
        if (eventKind == null) {
            throw new IllegalArgumentException("eventKind is required");
        }
        return of(productLine, exportSequence, eventKind.name(), entityUserDiscriminator, itemIndex);
    }

    public static String of(ProductLine productLine, long exportSequence, String eventKind,
                            String entityUserDiscriminator, int itemIndex) {
        if (productLine == null || exportSequence <= 0 || eventKind == null || eventKind.isBlank()
                || entityUserDiscriminator == null || entityUserDiscriminator.isBlank() || itemIndex < 0) {
            throw new IllegalArgumentException("invalid Core WebSocket event identity");
        }
        return "ws1|" + field(productLine.name()) + field(Long.toString(exportSequence))
                + field(eventKind) + field(entityUserDiscriminator) + field(Integer.toString(itemIndex));
    }

    public static UUID uuid(ProductLine productLine, long exportSequence, EventKind eventKind,
                            String entityUserDiscriminator, int itemIndex) {
        return uuid(of(productLine, exportSequence, eventKind, entityUserDiscriminator, itemIndex));
    }

    public static UUID uuid(String canonicalId) {
        if (canonicalId == null || canonicalId.isBlank()) {
            throw new IllegalArgumentException("canonicalId is required");
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(canonicalId.getBytes(StandardCharsets.UTF_8));
            digest[6] = (byte) ((digest[6] & 0x0f) | 0x50);
            digest[8] = (byte) ((digest[8] & 0x3f) | 0x80);
            ByteBuffer buffer = ByteBuffer.wrap(digest);
            return new UUID(buffer.getLong(), buffer.getLong());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String field(String value) {
        return value.length() + ":" + value + "|";
    }
}
