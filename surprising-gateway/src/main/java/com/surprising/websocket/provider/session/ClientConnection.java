package com.surprising.websocket.provider.session;

import lombok.extern.slf4j.Slf4j;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One connected browser/app session with a bounded async send queue.
 *
 * <p>The queue is intentionally bounded. A slow client is closed instead of allowing one WebSocket
 * connection to hold memory or block Kafka fanout for other sessions on the same node.</p>
 */
@Slf4j
public class ClientConnection implements AutoCloseable {

    private final WebSocketSession session;
    public static final String CLIENT_IP = ClientConnection.class.getName() + ".clientIp";
    private final String clientIp;
    private volatile Long authenticatedUserId;
    private record Authorization(String token, String apiKey, java.time.Instant expiresAt,
                                 long deadlineMillis, long refreshAtMillis) {}
    private volatile Authorization authorization;
    public record ApiChallenge(String challenge, java.time.Instant expiresAt) {}
    private ApiChallenge apiChallenge;
    private record LimitLease(long deadlineNanos, long refreshNanos) {}
    private volatile LimitLease limitLease;
    private long inboundAtNanos;
    private long inboundTokens;
    private int limitRejections;
    private volatile Runnable closedCallback = () -> {};
    /** 有界环形队列；满载时主动断开慢连接，背压不会传回 Kafka 消费线程。 */
    private final BlockingQueue<OutboundMessage> outbound;
    private final long sendTimeoutNanos;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ReentrantLock enqueueLock = new ReentrantLock();
    /** Writer and watcher share only the current send deadline, never the blocking socket write. */
    private final ReentrantLock sendStateLock = new ReentrantLock();
    private final Condition sendStateChanged = sendStateLock.newCondition();
    private long sendStartedAtNanos;
    private final Thread writerThread;
    private final Thread timeoutWatcher;

    public ClientConnection(WebSocketSession session,
                            Long authenticatedUserId,
                            int outboundQueueCapacity,
                            Duration sendTimeout) {
        this.session = session;
        this.clientIp = (String) session.getAttributes().get(CLIENT_IP);
        this.authenticatedUserId = authenticatedUserId;
        this.outbound = new ArrayBlockingQueue<>(Math.max(1, outboundQueueCapacity));
        this.sendTimeoutNanos = Math.max(1L, sendTimeout.toNanos());
        this.writerThread = Thread.ofVirtual().name("ws-send-" + session.getId()).unstarted(this::drain);
        this.timeoutWatcher = Thread.ofVirtual().name("ws-timeout-" + session.getId()).unstarted(this::watchSendTimeout);
        timeoutWatcher.start();
        writerThread.start();
    }

    public String id() {
        return session.getId();
    }

    public Long authenticatedUserId() {
        return authenticatedUserId;
    }

    public String accessToken() {var current = authorization; return current == null ? null : current.token();}
    public String apiKey() { var current = authorization; return current == null ? null : current.apiKey(); }
    public String clientIp() { return clientIp; }
    public void renewLimits(long deadlineNanos) {
        long now = System.nanoTime();
        limitLease = new LimitLease(deadlineNanos, now + Math.max(0, deadlineNanos - now) / 3);
    }
    public boolean limitRefreshDue(long now) { var lease = limitLease; return lease != null && now >= lease.refreshNanos(); }
    public boolean limitLeaseExpired(long now) { var lease = limitLease; return lease != null && now >= lease.deadlineNanos(); }
    public synchronized long consumeInbound(int rate, int burst, long periodMillis) {
        long now = System.nanoTime();
        long capacity = burst * periodMillis;
        long elapsed = Math.max(0, now - inboundAtNanos) / 1_000_000L;
        long refill = Math.min(elapsed, (capacity + rate - 1) / rate);
        inboundTokens = inboundAtNanos == 0 ? capacity : Math.min(capacity, inboundTokens + refill * rate);
        inboundAtNanos = inboundAtNanos == 0 ? now : inboundAtNanos + elapsed * 1_000_000L;
        if (inboundTokens < periodMillis) return Math.max(1, (periodMillis - inboundTokens + rate - 1) / rate);
        inboundTokens -= periodMillis;
        return 0;
    }
    public synchronized boolean rejectLimit() { return ++limitRejections >= 3; }
    public synchronized void admittedMessage() { limitRejections = 0; }
    public synchronized ApiChallenge issueApiChallenge() {
        if (authenticatedUserId != null) throw new IllegalStateException("websocket session is already authenticated");
        apiChallenge = new ApiChallenge(java.util.UUID.randomUUID().toString(), java.time.Instant.now().plusSeconds(60));
        return apiChallenge;
    }
    public synchronized ApiChallenge apiChallenge() {
        if (apiChallenge == null || !apiChallenge.expiresAt().isAfter(java.time.Instant.now()))
            throw new IllegalArgumentException("request a fresh websocket API challenge");
        return apiChallenge;
    }

    public synchronized void authenticateApiKey(long userId, String key, ApiChallenge challenge) {
        if (authenticatedUserId != null || !java.util.Objects.equals(apiChallenge(), challenge))
            throw new IllegalArgumentException("websocket API challenge was already consumed or replaced");
        authenticate(userId);
        apiChallenge = null;
        authorization = authorization(null, key, java.time.Instant.now().plusSeconds(900));
        if (!open.get()) { authorization = null; throw new IllegalStateException("websocket session is closed"); }
    }
    /** Reuses the existing timeout watcher so cleanup is independent of socket.close and authorization I/O. */
    public void onClosed(Runnable cleanup) {
        closedCallback = java.util.Objects.requireNonNull(cleanup, "cleanup");
        if (!open.get()) cleanup.run();
    }
    public boolean isOpen() {return open.get();}
    public synchronized void authenticate(long userId, String token, java.time.Instant expiresAt) {
        if (apiKey() != null) throw new IllegalArgumentException("reconnect to change websocket authentication");
        authenticate(userId);
        apiChallenge = null;
        authorization = authorization(java.util.Objects.requireNonNull(token, "token"), null, expiresAt);
        if (!open.get()) {
            authorization = null;
            throw new IllegalStateException("websocket session is closed");
        }
    }

    /** One immutable cold-path publication; senders never observe a new token with an old deadline. */
    private static Authorization authorization(String token, String apiKey, java.time.Instant expiresAt) {
        long now = System.currentTimeMillis();
        long expiry = expiresAt.toEpochMilli();
        return new Authorization(token, apiKey, expiresAt, Math.min(expiry, now + 5000), Math.min(expiry, now + 3000));
    }

    public synchronized void renewAuthorization(String checkedToken, java.time.Instant expiresAt) {
        var current = authorization;
        if (current == null || current.apiKey() != null || !java.util.Objects.equals(current.token(), checkedToken)) return;
        authorization = authorization(checkedToken, null, expiresAt);
        if (!open.get()) authorization = null;
    }

    public synchronized void renewApiKeyAuthorization(String checkedKey) {
        var current = authorization;
        if (current == null || !java.util.Objects.equals(current.apiKey(), checkedKey)) return;
        authorization = authorization(null, checkedKey, current.expiresAt());
        if (!open.get()) authorization = null;
    }

    public boolean authorizationRefreshDue(long now) {
        var current = authorization;
        return current != null && now >= current.refreshAtMillis();
    }

    public boolean authorizationExpired(long now) {
        var current = authorization;
        return current != null && now >= current.deadlineMillis();
    }

    public synchronized void authenticate(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("websocket userId must be positive");
        }
        if (authenticatedUserId != null && authenticatedUserId != userId) {
            throw new IllegalArgumentException("websocket session is already authenticated");
        }
        authenticatedUserId = userId;
    }

    public boolean send(String payload) {
        return sendBatch(List.of(payload));
    }

    /** 批量投递消息，避免同一连接在一次 fanout 中反复争用队列。 */
    public boolean sendBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) return true;
        for (String payload : payloads) java.util.Objects.requireNonNull(payload, "payload");
        enqueueLock.lock();
        try {
            if (!open.get()) return false;
            if (limitLeaseExpired(System.nanoTime())) {
                close(CloseStatus.SERVICE_OVERLOAD.withReason("websocket limit lease expired"));
                return false;
            }
            if (authorizationExpired(System.currentTimeMillis())) {
                close(CloseStatus.POLICY_VIOLATION.withReason("websocket authorization expired"));
                return false;
            }
            if (outbound.remainingCapacity() < payloads.size()) {
                close(CloseStatus.SERVICE_OVERLOAD);
                return false;
            }
            for (String payload : payloads) {
                if (!outbound.offer(new OutboundMessage(payload, System.nanoTime()))) {
                    close(CloseStatus.SERVICE_OVERLOAD);
                    return false;
                }
            }
            return true;
        } finally {
            enqueueLock.unlock();
        }
    }

    public int queuedMessages() {
        return outbound.size();
    }

    public int queueCapacity() {
        return outbound.size() + outbound.remainingCapacity();
    }

    private record OutboundMessage(String payload, long queuedNanos) {}

    private void drain() {
        while (open.get()) {
            try {
                OutboundMessage message = outbound.take();
                long started = System.nanoTime();
                try {
                    if (!sendWithinTimeout(message.payload)) {
                        log.warn("ws.send.end session={} result=TIMEOUT queueMs={}", session.getId(),
                                (started - message.queuedNanos) / 1_000_000.0);
                        close(CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
                        return;
                    }
                    if (log.isDebugEnabled()) log.debug("ws.send.end session={} result=OK queueMs={} sendMs={}", session.getId(),
                            (started - message.queuedNanos) / 1_000_000.0, (System.nanoTime() - started) / 1_000_000.0);
                } catch (Exception failure) {
                    log.warn("ws.send.end session={} result=FAILED error={}", session.getId(), failure.getClass().getSimpleName());
                    throw failure;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                close(CloseStatus.GOING_AWAY);
            } catch (Exception ex) {
                log.warn("closing websocket session={} after send failure error={}", session.getId(), ex.getClass().getSimpleName());
                close(CloseStatus.SERVER_ERROR.withReason("send failure"));
            }
        }
    }

    private void watchSendTimeout() {
        sendStateLock.lock();
        try {
            while (open.get()) {
                if (sendStartedAtNanos == 0L) {
                    sendStateChanged.await();
                } else {
                    long remaining = sendTimeoutNanos - (System.nanoTime() - sendStartedAtNanos);
                    if (remaining <= 0L) {
                        // Claim the timeout under the same lock used to start/finish a send.
                        // A completed send or a subsequent send cannot inherit an old deadline.
                        close(CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
                        return;
                    }
                    sendStateChanged.awaitNanos(remaining);
                }
            }
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        } finally {
            sendStateLock.unlock();
            // Reuse this existing per-socket watcher for cleanup, independent of socket.close().
            boolean interrupted = Thread.interrupted();
            try { closedCallback.run(); }
            finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }

    private boolean sendWithinTimeout(String payload) throws Exception {
        sendStateLock.lock();
        try {
            if (limitLeaseExpired(System.nanoTime()))
                close(CloseStatus.SERVICE_OVERLOAD.withReason("websocket limit lease expired"));
            if (authorizationExpired(System.currentTimeMillis()))
                close(CloseStatus.POLICY_VIOLATION.withReason("websocket authorization expired"));
            if (!open.get() || !session.isOpen()) {
                return false;
            }
            sendStartedAtNanos = System.nanoTime();
            sendStateChanged.signal();
        } finally {
            sendStateLock.unlock();
        }
        try {
            session.sendMessage(new TextMessage(payload));
            return open.get();
        } finally {
            sendStateLock.lock();
            try {
                sendStartedAtNanos = 0L;
                sendStateChanged.signal();
            } finally {
                sendStateLock.unlock();
            }
        }
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    public void close(CloseStatus status) {
        sendStateLock.lock();
        try {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            sendStateChanged.signal();
        } finally {
            sendStateLock.unlock();
        }
        authorization=null;
        outbound.clear();
        writerThread.interrupt();
        timeoutWatcher.interrupt();
        Thread.ofVirtual().name("websocket-close-" + session.getId()).start(() -> {
            try {
                if (session.isOpen()) session.close(status);
            } catch (IOException ex) {
                log.debug("failed to close websocket session={}: {}", session.getId(), ex.getMessage());
            }
        });
    }
}
