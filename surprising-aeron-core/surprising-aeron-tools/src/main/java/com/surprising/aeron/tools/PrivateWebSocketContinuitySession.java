package com.surprising.aeron.tools;

import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

public final class PrivateWebSocketContinuitySession {

    private final String clientId;
    private final long expectedUserId;
    private final Set<String> requiredChannels;
    private final Set<String> subscribedChannels = new HashSet<>();
    private boolean connected;
    private boolean connectedBefore;
    private boolean authenticated;
    private long authenticatedAtEpochMillis = -1;
    private long subscribedAtEpochMillis = -1;
    private long firstOrderSubmittedAtEpochMillis = -1;
    private long reconnects;
    private long authenticationFailures;
    private long queueRejections;

    public PrivateWebSocketContinuitySession(String clientId, long expectedUserId, Set<String> requiredChannels) {
        this.clientId = requireText(clientId, "clientId");
        if (expectedUserId <= 0) {
            throw new IllegalArgumentException("expectedUserId must be positive");
        }
        this.expectedUserId = expectedUserId;
        this.requiredChannels = Set.copyOf(Objects.requireNonNull(requiredChannels, "requiredChannels"));
        if (this.requiredChannels.isEmpty() || this.requiredChannels.stream().anyMatch(String::isBlank)) {
            throw new IllegalArgumentException("requiredChannels must contain non-blank channels");
        }
    }

    public synchronized void connected() {
        if (connected) {
            throw new IllegalStateException("WebSocket client is already connected");
        }
        if (connectedBefore) {
            reconnects++;
        }
        connectedBefore = true;
        connected = true;
        authenticated = false;
        subscribedChannels.clear();
        authenticatedAtEpochMillis = -1;
        subscribedAtEpochMillis = -1;
        firstOrderSubmittedAtEpochMillis = -1;
    }

    public synchronized void authenticated(long actualUserId, long atEpochMillis) {
        requireConnected();
        if (actualUserId != expectedUserId) {
            authenticationFailures++;
            throw new IllegalArgumentException("authenticated user mismatch");
        }
        authenticated = true;
        authenticatedAtEpochMillis = requireTimestamp(atEpochMillis);
    }

    public synchronized void authenticationRejected() {
        requireConnected();
        authenticationFailures++;
        authenticated = false;
    }

    public synchronized void subscribed(String channel, long atEpochMillis) {
        requireConnected();
        if (!authenticated) {
            throw new IllegalStateException("WebSocket client is not authenticated");
        }
        if (!requiredChannels.contains(channel)) {
            throw new IllegalArgumentException("unexpected private subscription " + channel);
        }
        subscribedChannels.add(channel);
        subscribedAtEpochMillis = Math.max(subscribedAtEpochMillis, requireTimestamp(atEpochMillis));
    }

    public synchronized void submitOrders(long atEpochMillis, Runnable submission) {
        Objects.requireNonNull(submission, "submission");
        if (!ready()) {
            throw new IllegalStateException("WebSocket client is not subscribed to every private channel");
        }
        long submittedAt = requireTimestamp(atEpochMillis);
        if (submittedAt < subscribedAtEpochMillis) {
            throw new IllegalArgumentException("order submission precedes subscription acknowledgement");
        }
        if (firstOrderSubmittedAtEpochMillis < 0) {
            firstOrderSubmittedAtEpochMillis = submittedAt;
        }
        submission.run();
    }

    public synchronized void disconnected() {
        connected = false;
        authenticated = false;
        subscribedChannels.clear();
    }

    public synchronized void queueRejected() {
        queueRejections++;
    }

    public synchronized boolean ready() {
        return connected && authenticated && subscribedChannels.containsAll(requiredChannels);
    }

    public synchronized long firstOrderSubmittedAtEpochMillis() {
        return firstOrderSubmittedAtEpochMillis;
    }

    public synchronized long reconnects() {
        return reconnects;
    }

    public synchronized long authenticationFailures() {
        return authenticationFailures;
    }

    public synchronized ProductionChainContinuityAuditor.ClientCheckpoint checkpoint(
            String topic, long caughtUpCoreSequence) {
        if (!ready() || firstOrderSubmittedAtEpochMillis < 0) {
            throw new IllegalStateException("cannot checkpoint before subscription and order submission");
        }
        return new ProductionChainContinuityAuditor.ClientCheckpoint(clientId, requireText(topic, "topic"),
                expectedUserId, caughtUpCoreSequence, authenticatedAtEpochMillis, subscribedAtEpochMillis,
                firstOrderSubmittedAtEpochMillis, reconnects, authenticationFailures, queueRejections);
    }

    private void requireConnected() {
        if (!connected) {
            throw new IllegalStateException("WebSocket client is not connected");
        }
    }

    private static long requireTimestamp(long value) {
        if (value < 0) {
            throw new IllegalArgumentException("timestamp must be non-negative");
        }
        return value;
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
