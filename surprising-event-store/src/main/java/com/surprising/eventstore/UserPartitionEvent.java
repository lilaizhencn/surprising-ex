package com.surprising.eventstore;

import java.time.Instant;
import java.util.Arrays;

/** 本地 WAL 中的一条不可变用户事实事件。 */
public record UserPartitionEvent(
        UserPartitionKey partition,
        long sequence,
        String eventId,
        String eventType,
        byte[] payload,
        String fingerprint,
        Instant occurredAt) {

    public UserPartitionEvent {
        if (partition == null || sequence <= 0 || eventId == null || eventId.isBlank()
                || eventType == null || eventType.isBlank() || payload == null
                || fingerprint == null || fingerprint.isBlank() || occurredAt == null) {
            throw new IllegalArgumentException("invalid user partition event");
        }
        payload = Arrays.copyOf(payload, payload.length);
    }

    @Override
    public byte[] payload() {
        return Arrays.copyOf(payload, payload.length);
    }
}
