package com.surprising.aeron.tools;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class ProductionChainContinuityAuditor {

    public Report audit(Scope scope, List<Observation> observations, List<ClientCheckpoint> clients) {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(observations, "observations");
        Objects.requireNonNull(clients, "clients");
        Counters counters = new Counters();
        Map<Long, Observation> coreBySequence = new HashMap<>();
        Map<String, Observation> coreByEvent = new HashMap<>();
        Map<String, List<Observation>> kafkaByEvent = new HashMap<>();
        Map<String, List<Observation>> postgresByEvent = new HashMap<>();
        Map<ClientEvent, List<Observation>> webSocketByClientEvent = new HashMap<>();
        long previousCoreSequence = Long.MIN_VALUE;
        if (clients.isEmpty()) {
            counters.missingClientCheckpoints++;
        }

        for (Observation observation : observations) {
            switch (observation.layer()) {
                case CORE -> {
                    if (previousCoreSequence != Long.MIN_VALUE
                            && observation.coreSequence() < previousCoreSequence) {
                        counters.coreOutOfOrder++;
                    }
                    previousCoreSequence = observation.coreSequence();
                    Observation sameSequence = coreBySequence.putIfAbsent(observation.coreSequence(), observation);
                    if (sameSequence != null) {
                        counters.coreDuplicates++;
                        if (!sameFact(sameSequence, observation)) {
                            counters.mutations++;
                        }
                    }
                    Observation sameEvent = coreByEvent.putIfAbsent(observation.eventId(), observation);
                    if (sameEvent != null && !sameFact(sameEvent, observation)) {
                        counters.mutations++;
                    }
                }
                case KAFKA -> kafkaByEvent.computeIfAbsent(observation.eventId(), ignored -> new ArrayList<>())
                        .add(observation);
                case POSTGRES -> postgresByEvent.computeIfAbsent(observation.eventId(), ignored -> new ArrayList<>())
                        .add(observation);
                case WEBSOCKET -> webSocketByClientEvent.computeIfAbsent(
                                new ClientEvent(observation.clientId(), observation.eventId()),
                                ignored -> new ArrayList<>())
                        .add(observation);
            }
        }

        long coreInRange = coreBySequence.keySet().stream()
                .filter(sequence -> sequence >= scope.firstCoreSequence() && sequence <= scope.lastCoreSequence())
                .count();
        counters.coreMissing = scope.eventCount() - coreInRange;
        counters.unexpectedFacts += coreBySequence.size() - coreInRange;

        for (Observation core : coreByEvent.values()) {
            List<Observation> kafka = kafkaByEvent.getOrDefault(core.eventId(), List.of());
            if (kafka.isEmpty()) {
                counters.missingKafkaEvents++;
            } else {
                compareCopies(core, kafka, counters);
                counters.kafkaRedeliveries += identicalRedeliveries(kafka, counters);
            }
            if (core.coreSequence() <= scope.projectorWatermark()) {
                List<Observation> rows = postgresByEvent.getOrDefault(core.eventId(), List.of());
                if (rows.isEmpty()) {
                    counters.missingProjectedFacts++;
                } else {
                    compareCopies(core, rows, counters);
                    if (rows.size() > 1) {
                        counters.postgresDuplicateFacts += rows.size() - 1L;
                    }
                }
            }
        }

        countUnexpectedOrMutated(coreByEvent, kafkaByEvent, counters);
        countUnexpectedOrMutated(coreByEvent, postgresByEvent, counters);
        for (Map.Entry<ClientEvent, List<Observation>> entry : webSocketByClientEvent.entrySet()) {
            Observation core = coreByEvent.get(entry.getKey().eventId());
            boolean expectedReceipt = core != null && clients.stream().anyMatch(client ->
                    client.clientId().equals(entry.getKey().clientId())
                            && matchesSubscription(core, client)
                            && core.coreSequence() <= client.caughtUpCoreSequence());
            if (!expectedReceipt) {
                counters.unexpectedFacts += entry.getValue().size();
            }
        }

        Set<ClientSubscription> seenSubscriptions = new HashSet<>();
        for (ClientCheckpoint client : clients) {
            ClientSubscription subscription = new ClientSubscription(client.clientId(), client.topic(), client.userId());
            if (!seenSubscriptions.add(subscription)) {
                counters.duplicateClientCheckpoints++;
            }
            counters.reconnects += client.reconnects();
            counters.authenticationFailures += client.authenticationFailures();
            counters.queueRejections += client.queueRejections();
            if (client.authenticatedAtEpochMillis() > client.subscribedAtEpochMillis()
                    || client.subscribedAtEpochMillis() > client.firstOrderSubmittedAtEpochMillis()) {
                counters.subscriptionOrderViolations++;
            }
            long highestReceipt = -1L;
            for (Observation core : coreByEvent.values()) {
                if (!matchesSubscription(core, client) || core.coreSequence() > client.caughtUpCoreSequence()) {
                    continue;
                }
                List<Observation> receipts = webSocketByClientEvent.getOrDefault(
                        new ClientEvent(client.clientId(), core.eventId()), List.of());
                if (receipts.isEmpty()) {
                    counters.permanentWebSocketLosses++;
                    continue;
                }
                compareCopies(core, receipts, counters);
                counters.webSocketRedeliveries += identicalRedeliveries(receipts, counters);
                highestReceipt = Math.max(highestReceipt, receipts.stream()
                        .mapToLong(Observation::coreSequence)
                        .max()
                        .orElse(-1L));
            }
            if (highestReceipt < client.caughtUpCoreSequence()) {
                counters.staleCatchUps++;
            }
        }

        return counters.report();
    }

    private static void countUnexpectedOrMutated(Map<String, Observation> coreByEvent,
                                                  Map<String, List<Observation>> copiesByEvent,
                                                  Counters counters) {
        for (Map.Entry<String, List<Observation>> entry : copiesByEvent.entrySet()) {
            Observation core = coreByEvent.get(entry.getKey());
            if (core == null) {
                counters.unexpectedFacts += entry.getValue().size();
            }
        }
    }

    private static void compareCopies(Observation core, List<Observation> copies, Counters counters) {
        for (Observation copy : copies) {
            if (!sameFact(core, copy)) {
                counters.mutations++;
            }
        }
    }

    private static long identicalRedeliveries(List<Observation> copies, Counters counters) {
        if (copies.size() < 2) {
            return 0L;
        }
        Observation first = copies.getFirst();
        long redeliveries = 0L;
        for (int index = 1; index < copies.size(); index++) {
            if (sameFact(first, copies.get(index))) {
                redeliveries++;
            } else {
                counters.mutations++;
            }
        }
        return redeliveries;
    }

    private static boolean matchesSubscription(Observation core, ClientCheckpoint client) {
        return core.topic().equals(client.topic())
                && (core.userId() == null || Objects.equals(core.userId(), client.userId()));
    }

    private static boolean sameFact(Observation left, Observation right) {
        return left.eventId().equals(right.eventId())
                && left.coreSequence() == right.coreSequence()
                && left.topic().equals(right.topic())
                && Objects.equals(left.userId(), right.userId())
                && left.payloadSha256().equals(right.payloadSha256());
    }

    public enum Layer {
        CORE, KAFKA, POSTGRES, WEBSOCKET
    }

    public record Scope(long firstCoreSequence, long lastCoreSequence, long projectorWatermark) {
        public Scope {
            if (firstCoreSequence < 0 || lastCoreSequence < firstCoreSequence) {
                throw new IllegalArgumentException("invalid Core sequence range");
            }
            if (projectorWatermark < firstCoreSequence - 1 || projectorWatermark > lastCoreSequence) {
                throw new IllegalArgumentException("projector watermark must be inside the audit range");
            }
            try {
                Math.addExact(Math.subtractExact(lastCoreSequence, firstCoreSequence), 1L);
            } catch (ArithmeticException exception) {
                throw new IllegalArgumentException("Core sequence range is too large to count", exception);
            }
        }

        long eventCount() {
            return lastCoreSequence - firstCoreSequence + 1L;
        }
    }

    public record Observation(Layer layer, String eventId, long coreSequence, String topic, Long userId,
                              String payloadSha256, String clientId, int kafkaPartition, Long kafkaOffset) {
        public Observation {
            Objects.requireNonNull(layer, "layer");
            requireText(eventId, "eventId");
            requireText(topic, "topic");
            requireText(payloadSha256, "payloadSha256");
            if (coreSequence < 0) {
                throw new IllegalArgumentException("coreSequence must be non-negative");
            }
            if (layer == Layer.WEBSOCKET) {
                requireText(clientId, "clientId");
            }
            if (layer == Layer.KAFKA && (kafkaPartition < 0 || kafkaOffset == null || kafkaOffset < 0)) {
                throw new IllegalArgumentException("Kafka observation requires non-negative partition and offset");
            }
        }
    }

    public record ClientCheckpoint(String clientId, String topic, long userId, long caughtUpCoreSequence,
                                   long authenticatedAtEpochMillis, long subscribedAtEpochMillis,
                                   long firstOrderSubmittedAtEpochMillis, long reconnects,
                                   long authenticationFailures, long queueRejections) {
        public ClientCheckpoint {
            requireText(clientId, "clientId");
            requireText(topic, "topic");
            if (userId <= 0 || caughtUpCoreSequence < 0 || reconnects < 0
                    || authenticationFailures < 0 || queueRejections < 0) {
                throw new IllegalArgumentException("client checkpoint counts must be non-negative");
            }
        }
    }

    public record Report(long coreMissing, long coreDuplicates, long coreOutOfOrder, long mutations,
                         long kafkaRedeliveries, long missingKafkaEvents, long postgresDuplicateFacts,
                         long missingProjectedFacts, long webSocketRedeliveries, long permanentWebSocketLosses,
                         long reconnects, long authenticationFailures, long queueRejections,
                         long subscriptionOrderViolations, long staleCatchUps, long unexpectedFacts,
                         long duplicateClientCheckpoints, long missingClientCheckpoints) {
        public boolean passed() {
            return coreMissing == 0 && coreDuplicates == 0 && coreOutOfOrder == 0 && mutations == 0
                    && missingKafkaEvents == 0 && postgresDuplicateFacts == 0 && missingProjectedFacts == 0
                    && permanentWebSocketLosses == 0 && authenticationFailures == 0 && queueRejections == 0
                    && subscriptionOrderViolations == 0 && staleCatchUps == 0 && unexpectedFacts == 0
                    && duplicateClientCheckpoints == 0 && missingClientCheckpoints == 0;
        }
    }

    private record ClientEvent(String clientId, String eventId) { }
    private record ClientSubscription(String clientId, String topic, long userId) { }

    private static final class Counters {
        long coreMissing;
        long coreDuplicates;
        long coreOutOfOrder;
        long mutations;
        long kafkaRedeliveries;
        long missingKafkaEvents;
        long postgresDuplicateFacts;
        long missingProjectedFacts;
        long webSocketRedeliveries;
        long permanentWebSocketLosses;
        long reconnects;
        long authenticationFailures;
        long queueRejections;
        long subscriptionOrderViolations;
        long staleCatchUps;
        long unexpectedFacts;
        long duplicateClientCheckpoints;
        long missingClientCheckpoints;

        Report report() {
            return new Report(coreMissing, coreDuplicates, coreOutOfOrder, mutations, kafkaRedeliveries,
                    missingKafkaEvents, postgresDuplicateFacts, missingProjectedFacts, webSocketRedeliveries,
                    permanentWebSocketLosses, reconnects, authenticationFailures, queueRejections,
                    subscriptionOrderViolations, staleCatchUps, unexpectedFacts, duplicateClientCheckpoints,
                    missingClientCheckpoints);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }
}
