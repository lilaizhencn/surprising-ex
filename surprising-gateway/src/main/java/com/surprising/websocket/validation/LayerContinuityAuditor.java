package com.surprising.websocket.validation;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class LayerContinuityAuditor {

    public Report audit(List<WebSocketAuditRecord> records) {
        Objects.requireNonNull(records, "records");
        Counters counters = new Counters();
        Map<String, WebSocketAuditRecord> coreByEvent = new HashMap<>();
        Map<String, WebSocketAuditRecord> kafkaByEvent = new HashMap<>();
        Map<String, WebSocketAuditRecord> projectorByEvent = new HashMap<>();
        Map<String, WebSocketAuditRecord> webSocketByClientEvent = new HashMap<>();
        Map<Long, WebSocketAuditRecord> coreBySequence = new HashMap<>();
        long highestCoreSequence = -1L;
        long projectorWatermark = -1L;

        for (WebSocketAuditRecord record : records) {
            switch (record.type()) {
                case RECONNECT -> counters.reconnects++;
                case AUTH_FAILURE -> counters.authenticationFailures++;
                case QUEUE_REJECTION -> counters.queueRejections++;
                case CATCH_UP -> { }
                case EVENT -> {
                    switch (record.layer()) {
                        case CORE -> {
                            long sequence = record.coreSequence();
                            WebSocketAuditRecord previousSequence = coreBySequence.putIfAbsent(sequence, record);
                            if (previousSequence != null) {
                                counters.coreDuplicates++;
                                if (!sameEvent(previousSequence, record)) {
                                    counters.mutations++;
                                }
                            } else if (highestCoreSequence >= 0L && sequence > highestCoreSequence + 1L) {
                                counters.coreGaps += sequence - highestCoreSequence - 1L;
                            } else if (highestCoreSequence >= 0L && sequence < highestCoreSequence) {
                                counters.coreOutOfOrder++;
                            }
                            highestCoreSequence = Math.max(highestCoreSequence, sequence);
                            WebSocketAuditRecord previousEvent = coreByEvent.putIfAbsent(record.eventId(), record);
                            if (previousEvent != null && previousSequence == null
                                    && !sameEvent(previousEvent, record)) {
                                counters.mutations++;
                            }
                        }
                        case KAFKA -> {
                            if (duplicate(record, kafkaByEvent, counters, true)) {
                                counters.kafkaRedeliveries++;
                            }
                        }
                        case PROJECTOR -> {
                            projectorWatermark = Math.max(projectorWatermark,
                                    record.projectorWatermark() == null ? -1L : record.projectorWatermark());
                            if (record.projectedRows() == null || record.projectedRows() == 0) {
                                counters.missingProjectedFacts++;
                            } else if (record.projectedRows() > 1) {
                                counters.projectorDuplicateFacts += record.projectedRows() - 1L;
                            }
                            duplicate(record, projectorByEvent, counters, false);
                        }
                        case WEBSOCKET -> {
                            String key = record.clientId() + "\n" + record.eventId();
                            WebSocketAuditRecord previous = webSocketByClientEvent.putIfAbsent(key, record);
                            if (previous != null) {
                                if (sameEvent(previous, record)) {
                                    counters.webSocketRedeliveries++;
                                } else {
                                    counters.mutations++;
                                }
                            }
                        }
                    }
                }
            }
        }

        if (projectorWatermark >= 0L) {
            for (WebSocketAuditRecord core : coreByEvent.values()) {
                if (core.coreSequence() <= projectorWatermark) {
                    if (!kafkaByEvent.containsKey(core.eventId())) {
                        counters.missingKafkaEvents++;
                    }
                    if (!projectorByEvent.containsKey(core.eventId())) {
                        counters.missingProjectedFacts++;
                    }
                }
            }
        }

        for (WebSocketAuditRecord catchUp : records) {
            if (catchUp.type() != WebSocketAuditRecord.Type.CATCH_UP) {
                continue;
            }
            for (WebSocketAuditRecord core : coreByEvent.values()) {
                if (core.coreSequence() <= catchUp.coreSequence()
                        && core.topic().equals(catchUp.topic())
                        && (core.userId() == null || Objects.equals(core.userId(), catchUp.userId()))) {
                    String key = catchUp.clientId() + "\n" + core.eventId();
                    if (!webSocketByClientEvent.containsKey(key)) {
                        counters.permanentWebSocketLosses++;
                    }
                }
            }
        }

        return counters.report();
    }

    private static boolean duplicate(WebSocketAuditRecord record,
                                     Map<String, WebSocketAuditRecord> byEvent,
                                     Counters counters,
                                     boolean legalIdenticalReplay) {
        WebSocketAuditRecord previous = byEvent.putIfAbsent(record.eventId(), record);
        if (previous == null) {
            return false;
        }
        if (!sameEvent(previous, record)) {
            counters.mutations++;
            return false;
        }
        return legalIdenticalReplay;
    }

    private static boolean sameEvent(WebSocketAuditRecord left, WebSocketAuditRecord right) {
        return Objects.equals(left.eventId(), right.eventId())
                && Objects.equals(left.coreSequence(), right.coreSequence())
                && Objects.equals(left.topic(), right.topic())
                && Objects.equals(left.userId(), right.userId())
                && Objects.equals(left.payloadSha256(), right.payloadSha256());
    }

    public record Report(
            long coreGaps,
            long coreDuplicates,
            long coreOutOfOrder,
            long mutations,
            long kafkaRedeliveries,
            long missingKafkaEvents,
            long projectorDuplicateFacts,
            long missingProjectedFacts,
            long webSocketRedeliveries,
            long permanentWebSocketLosses,
            long reconnects,
            long queueRejections,
            long authenticationFailures) {

        public boolean passed() {
            return coreGaps == 0L && coreDuplicates == 0L && coreOutOfOrder == 0L && mutations == 0L
                    && missingKafkaEvents == 0L && projectorDuplicateFacts == 0L && missingProjectedFacts == 0L
                    && permanentWebSocketLosses == 0L && queueRejections == 0L && authenticationFailures == 0L;
        }
    }

    private static final class Counters {
        private long coreGaps;
        private long coreDuplicates;
        private long coreOutOfOrder;
        private long mutations;
        private long kafkaRedeliveries;
        private long missingKafkaEvents;
        private long projectorDuplicateFacts;
        private long missingProjectedFacts;
        private long webSocketRedeliveries;
        private long permanentWebSocketLosses;
        private long reconnects;
        private long queueRejections;
        private long authenticationFailures;

        private Report report() {
            return new Report(coreGaps, coreDuplicates, coreOutOfOrder, mutations, kafkaRedeliveries,
                    missingKafkaEvents, projectorDuplicateFacts, missingProjectedFacts,
                    webSocketRedeliveries, permanentWebSocketLosses, reconnects, queueRejections,
                    authenticationFailures);
        }
    }
}
