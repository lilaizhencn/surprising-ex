package com.surprising.aeron.protocol;

import java.util.List;
import java.util.Objects;

public record CoreExportBatch(CoreExportStatus status, List<CoreMessage> events) {
    public CoreExportBatch {
        Objects.requireNonNull(status, "status");
        if (events == null) {
            throw new IllegalArgumentException("invalid export batch");
        }
        events = List.copyOf(events);
    }

    public long acknowledgedSequence() {
        return status.acknowledgedSequence();
    }
}
