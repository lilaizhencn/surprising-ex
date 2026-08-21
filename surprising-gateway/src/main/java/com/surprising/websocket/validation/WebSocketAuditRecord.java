package com.surprising.websocket.validation;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;

public record WebSocketAuditRecord(
        Type type,
        Layer layer,
        String clientId,
        String eventId,
        Long coreSequence,
        String topic,
        Long userId,
        Long sentAtEpochMillis,
        Long receivedAtEpochMillis,
        String payloadSha256,
        Long kafkaOffset,
        Long projectorWatermark,
        Integer projectedRows,
        String detail) {

    public WebSocketAuditRecord {
        Objects.requireNonNull(type, "type");
        if (type == Type.EVENT) {
            Objects.requireNonNull(layer, "layer");
            requireText(eventId, "eventId");
            if (coreSequence == null || coreSequence < 0L) {
                throw new IllegalArgumentException("coreSequence must be non-negative");
            }
            requireText(topic, "topic");
            requireText(payloadSha256, "payloadSha256");
            if (sentAtEpochMillis == null || receivedAtEpochMillis == null
                    || receivedAtEpochMillis < sentAtEpochMillis) {
                throw new IllegalArgumentException("event send/receive times are invalid");
            }
            if (layer == Layer.WEBSOCKET) {
                requireText(clientId, "clientId");
            }
            if (projectedRows != null && projectedRows < 0) {
                throw new IllegalArgumentException("projectedRows must be non-negative");
            }
        } else {
            requireText(clientId, "clientId");
        }
    }

    public static WebSocketAuditRecord event(Layer layer,
                                             String clientId,
                                             String eventId,
                                             long coreSequence,
                                             String topic,
                                             Long userId,
                                             long sentAtEpochMillis,
                                             long receivedAtEpochMillis,
                                             String payloadSha256,
                                             Long kafkaOffset,
                                             Long projectorWatermark,
                                             Integer projectedRows) {
        return new WebSocketAuditRecord(Type.EVENT, layer, clientId, eventId, coreSequence, topic, userId,
                sentAtEpochMillis, receivedAtEpochMillis, payloadSha256, kafkaOffset,
                projectorWatermark, projectedRows, null);
    }

    public static WebSocketAuditRecord catchUp(String clientId, Long userId, String topic, long coreSequence) {
        if (coreSequence < 0L) {
            throw new IllegalArgumentException("catch-up sequence must be non-negative");
        }
        requireText(topic, "topic");
        return new WebSocketAuditRecord(Type.CATCH_UP, null, clientId, null, coreSequence, topic, userId,
                null, System.currentTimeMillis(), null, null, null, null, null);
    }

    public static WebSocketAuditRecord signal(Type type, String clientId, Long userId, String detail) {
        if (type == Type.EVENT || type == Type.CATCH_UP) {
            throw new IllegalArgumentException("signal type is invalid: " + type);
        }
        return new WebSocketAuditRecord(type, null, clientId, null, null, null, userId,
                null, System.currentTimeMillis(), null, null, null, null, detail);
    }

    public static String sha256(String value) {
        Objects.requireNonNull(value, "value");
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    public enum Type {
        EVENT,
        CATCH_UP,
        RECONNECT,
        AUTH_FAILURE,
        QUEUE_REJECTION
    }

    public enum Layer {
        CORE,
        KAFKA,
        PROJECTOR,
        WEBSOCKET
    }
}
