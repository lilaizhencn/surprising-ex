package com.surprising.websocket.provider.service;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * One connected browser/app session with a bounded async send queue.
 *
 * <p>The queue is intentionally bounded. A slow client is closed instead of allowing one WebSocket
 * connection to hold memory or block Kafka fanout for other sessions on the same node.</p>
 */
public class ClientConnection implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(ClientConnection.class);

    private final WebSocketSession session;
    private final Long authenticatedUserId;
    private final BlockingQueue<String> outbound;
    private final Duration sendTimeout;
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final ReentrantLock sendLock = new ReentrantLock();
    private final Thread writerThread;

    public ClientConnection(WebSocketSession session,
                            Long authenticatedUserId,
                            int outboundQueueCapacity,
                            Duration sendTimeout) {
        this.session = session;
        this.authenticatedUserId = authenticatedUserId;
        this.outbound = new ArrayBlockingQueue<>(Math.max(1, outboundQueueCapacity));
        this.sendTimeout = sendTimeout;
        this.writerThread = Thread.ofVirtual().name("ws-send-" + session.getId()).start(this::drain);
    }

    public String id() {
        return session.getId();
    }

    public Long authenticatedUserId() {
        return authenticatedUserId;
    }

    public boolean send(String payload) {
        if (!open.get()) {
            return false;
        }
        boolean accepted = outbound.offer(payload);
        if (!accepted) {
            close(CloseStatus.SERVICE_OVERLOAD.withReason("websocket outbound queue full"));
        }
        return accepted;
    }

    private void drain() {
        while (open.get()) {
            try {
                String payload = outbound.poll(1, TimeUnit.SECONDS);
                if (payload == null) {
                    continue;
                }
                if (!sendWithinTimeout(payload)) {
                    close(CloseStatus.SESSION_NOT_RELIABLE.withReason("websocket send timeout"));
                    return;
                }
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                close(CloseStatus.GOING_AWAY);
            } catch (Exception ex) {
                log.warn("closing websocket session={} after send failure: {}", session.getId(), ex.getMessage());
                close(CloseStatus.SERVER_ERROR.withReason("send failure"));
            }
        }
    }

    private boolean sendWithinTimeout(String payload) throws Exception {
        Thread sender = Thread.currentThread();
        long timeoutMillis = Math.max(1L, sendTimeout.toMillis());
        AtomicBoolean sent = new AtomicBoolean(false);
        Thread watchdog = Thread.ofVirtual().start(() -> {
            try {
                Thread.sleep(timeoutMillis);
                if (!sent.get()) {
                    sender.interrupt();
                }
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
        });
        try {
            sendLock.lockInterruptibly();
            try {
                if (!session.isOpen()) {
                    return false;
                }
                session.sendMessage(new TextMessage(payload));
            } finally {
                sendLock.unlock();
            }
            return true;
        } finally {
            sent.set(true);
            watchdog.interrupt();
        }
    }

    @Override
    public void close() {
        close(CloseStatus.NORMAL);
    }

    public void close(CloseStatus status) {
        if (!open.compareAndSet(true, false)) {
            return;
        }
        writerThread.interrupt();
        try {
            if (session.isOpen()) {
                session.close(status);
            }
        } catch (IOException ex) {
            log.debug("failed to close websocket session={}: {}", session.getId(), ex.getMessage());
        }
    }
}
