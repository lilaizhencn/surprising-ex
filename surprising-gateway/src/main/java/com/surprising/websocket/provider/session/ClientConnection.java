package com.surprising.websocket.provider.session;

import io.netty.buffer.ByteBuf;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * One connected browser/app session backed by a non-blocking {@link WsTransportSession}.
 *
 * <p>Backpressure is enforced by the underlying transport (Netty write-buffer watermarks in
 * production): a slow client is closed immediately instead of allowing one WebSocket connection to
 * hold memory or block Kafka fanout for other sessions on the same node.</p>
 */
public class ClientConnection implements AutoCloseable {

    private final WsTransportSession session;
    public static final String CLIENT_IP = ClientConnection.class.getName() + ".clientIp";
    /** 握手时浏览器带来的登录 Cookie 令牌；网页端 JavaScript 读不到它，认证时由服务端代为使用。 */
    public static final String BROWSER_SESSION_TOKEN = ClientConnection.class.getName() + ".browserSessionToken";
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
    private final AtomicBoolean open = new AtomicBoolean(true);

    public ClientConnection(WsTransportSession session, Long authenticatedUserId) {
        this.session = Objects.requireNonNull(session, "session");
        this.clientIp = session.clientIp();
        this.authenticatedUserId = authenticatedUserId;
    }

    public ClientConnection(WsTransportSession session,
                            Long authenticatedUserId,
                            int ignoredOutboundQueueCapacity,
                            Duration ignoredSendTimeout) {
        this(session, authenticatedUserId);
    }

    public String id() {
        return session.id();
    }

    public Long authenticatedUserId() {
        return authenticatedUserId;
    }

    public String accessToken() { var current = authorization; return current == null ? null : current.token(); }
    public String apiKey() { var current = authorization; return current == null ? null : current.apiKey(); }
    public String clientIp() { return clientIp; }
    public String browserSessionToken() { return session.browserSessionToken(); }

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
        if (authenticatedUserId != null || !Objects.equals(apiChallenge(), challenge))
            throw new IllegalArgumentException("websocket API challenge was already consumed or replaced");
        authenticate(userId);
        apiChallenge = null;
        authorization = authorization(null, key, java.time.Instant.now().plusSeconds(900));
        if (!open.get()) { authorization = null; throw new IllegalStateException("websocket session is closed"); }
    }

    public void onClosed(Runnable cleanup) {
        closedCallback = Objects.requireNonNull(cleanup, "cleanup");
        if (!open.get()) cleanup.run();
    }

    public boolean isOpen() { return open.get() && session.isOpen(); }

    public synchronized void authenticate(long userId, String token, java.time.Instant expiresAt) {
        if (apiKey() != null) throw new IllegalArgumentException("reconnect to change websocket authentication");
        authenticate(userId);
        apiChallenge = null;
        authorization = authorization(Objects.requireNonNull(token, "token"), null, expiresAt);
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
        if (current == null || current.apiKey() != null || !Objects.equals(current.token(), checkedToken)) return;
        authorization = authorization(checkedToken, null, expiresAt);
        if (!open.get()) authorization = null;
    }

    public synchronized void renewApiKeyAuthorization(String checkedKey) {
        var current = authorization;
        if (current == null || !Objects.equals(current.apiKey(), checkedKey)) return;
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

    /** 批量投递消息到非阻塞传输层，背压超限时主动断开慢连接。 */
    public boolean sendBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) return true;
        for (String payload : payloads) Objects.requireNonNull(payload, "payload");
        if (!preSendCheck()) return false;
        if (!session.sendBatch(payloads)) {
            close(WsCloseStatus.SERVICE_OVERLOAD);
            return false;
        }
        return true;
    }

    public boolean supportsZeroCopyFrames() {
        return session.supportsZeroCopyFrames();
    }

    /** 零拷贝广播预编码 UTF-8 ByteBuf 帧（Netty 通道复用同一份 ByteBuf）。 */
    public boolean sendFrames(List<ByteBuf> sharedFrames, List<String> fallbackPayloads) {
        if (sharedFrames == null || sharedFrames.isEmpty()) return true;
        if (!preSendCheck()) return false;
        if (!session.sendFrames(sharedFrames, fallbackPayloads)) {
            close(WsCloseStatus.SERVICE_OVERLOAD);
            return false;
        }
        return true;
    }

    private boolean preSendCheck() {
        if (!open.get() || !session.isOpen()) return false;
        if (limitLeaseExpired(System.nanoTime())) {
            close(WsCloseStatus.SERVICE_OVERLOAD.withReason("websocket limit lease expired"));
            return false;
        }
        if (authorizationExpired(System.currentTimeMillis())) {
            close(WsCloseStatus.POLICY_VIOLATION.withReason("websocket authorization expired"));
            return false;
        }
        return true;
    }

    public int queuedMessages() {
        return session.queuedMessages();
    }

    public int queueCapacity() {
        return session.queueCapacity();
    }

    @Override
    public void close() {
        close(WsCloseStatus.NORMAL);
    }

    public void close(WsCloseStatus status) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        authorization = null;
        try {
            if (session.isOpen()) {
                session.close(status == null ? WsCloseStatus.NORMAL : status);
            }
        } finally {
            closedCallback.run();
        }
    }
}
