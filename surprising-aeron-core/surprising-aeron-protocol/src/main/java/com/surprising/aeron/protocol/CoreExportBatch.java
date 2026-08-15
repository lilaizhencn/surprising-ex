package com.surprising.aeron.protocol;

import java.util.List;

public record CoreExportBatch(long acknowledgedSequence, List<CoreMessage> events) {
    public CoreExportBatch {
        if (acknowledgedSequence < 0 || events == null) {
            throw new IllegalArgumentException("invalid export batch");
        }
        events = List.copyOf(events);
    }
}
