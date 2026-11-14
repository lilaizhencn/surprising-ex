package com.surprising.websocket.provider.session;

import io.netty.buffer.ByteBuf;
import java.util.List;

/**
 * Transport abstraction for a WebSocket connection, implemented by Netty in production
 * and lightweight test doubles in unit tests.
 */
public interface WsTransportSession {

    String id();

    String clientIp();

    default String browserSessionToken() {
        return null;
    }

    boolean isOpen();

    boolean sendText(String payload);

    default boolean sendBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return true;
        }
        for (String payload : payloads) {
            if (!sendText(payload)) {
                return false;
            }
        }
        return true;
    }

    default boolean supportsZeroCopyFrames() {
        return false;
    }

    /**
     * Zero-copy broadcast hook for Netty channels: sends retained duplicates of pre-encoded UTF-8 frames
     * and flushes once. Callers retain ownership of {@code sharedFrames} and release them after fanout.
     */
    default boolean sendFrames(List<ByteBuf> sharedFrames, List<String> fallbackPayloads) {
        return sendBatch(fallbackPayloads);
    }

    default int queuedMessages() {
        return 0;
    }

    default int queueCapacity() {
        return 0;
    }

    void close(WsCloseStatus status);

    default void close() {
        close(WsCloseStatus.NORMAL);
    }
}
