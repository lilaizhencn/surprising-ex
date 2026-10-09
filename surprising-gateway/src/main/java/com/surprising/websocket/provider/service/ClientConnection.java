package com.surprising.websocket.provider.service;

import lombok.extern.slf4j.Slf4j;
import com.surprising.trading.api.TraceContext;

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
    private volatile Long authenticatedUserId;
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
        return send(new TracedMessage(payload, TraceContext.current()));
    }

    /** 批量投递消息，避免同一连接在一次 fanout 中反复争用队列。 */
    public boolean sendBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) return true;
        String traceId = TraceContext.current();
        return sendTracedBatch(payloads.stream().map(payload -> new TracedMessage(payload, traceId)).toList());
    }

    public boolean send(TracedMessage message) {
        return sendTracedBatch(List.of(message));
    }

    /** Serialized payload and its trace metadata cross the same queue boundary. */
    public boolean sendTracedBatch(List<TracedMessage> payloads) {
        if (payloads == null || payloads.isEmpty()) return true;
        enqueueLock.lock();
        try {
            if (!open.get()) {
                return false;
            }
            if (outbound.remainingCapacity() < payloads.size()) {
                close(CloseStatus.SERVICE_OVERLOAD);
                return false;
            }
            for (TracedMessage message : payloads) {
                if (!outbound.offer(new OutboundMessage(message.payload(), message.traceId(), System.nanoTime()))) {
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

    public record TracedMessage(String payload, String traceId) {
        public TracedMessage {
            java.util.Objects.requireNonNull(payload, "payload");
            traceId = TraceContext.normalizeOrCreate(traceId);
        }
    }

    private record OutboundMessage(String payload, String traceId, long queuedNanos) {}

    private void drain() {
        while (open.get()) {
            try {
                OutboundMessage message = outbound.take();
                String id = message.traceId;
                try (var trace = TraceContext.open(id)) {
                    long started = System.nanoTime();
                    try {
                        if (!sendWithinTimeout(message.payload)) {
                            log.warn("ws.send.end session={} result=TIMEOUT queueMs={}", session.getId(),
                                    (started - message.queuedNanos) / 1_000_000.0);
                            close(CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
                            return;
                        }
                        log.info("ws.send.end session={} result=OK queueMs={} sendMs={}", session.getId(),
                                (started - message.queuedNanos) / 1_000_000.0, (System.nanoTime() - started) / 1_000_000.0);
                    } catch (Exception failure) {
                        log.warn("ws.send.end session={} result=FAILED error={}", session.getId(), failure.getClass().getSimpleName());
                        throw failure;
                    }
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
        }
    }

    private boolean sendWithinTimeout(String payload) throws Exception {
        sendStateLock.lock();
        try {
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
