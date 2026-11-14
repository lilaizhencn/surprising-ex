package com.surprising.websocket.provider.netty;

import com.surprising.websocket.provider.session.WsCloseStatus;
import com.surprising.websocket.provider.session.WsTransportSession;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFutureListener;
import io.netty.handler.codec.http.websocketx.CloseWebSocketFrame;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Non-blocking Netty {@link Channel}-backed {@link WsTransportSession}.
 * Outbound writes happen directly via Netty's EventLoop with zero per-connection threads;
 * inbound commands execute sequentially on virtual threads so blocking auth/DB calls never
 * stall the Netty I/O EventLoop.
 */
public final class NettyWsTransportSession implements WsTransportSession {

    private final Channel channel;
    private final String id;
    private final String clientIp;
    private final String browserSessionToken;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final Queue<Runnable> inboundQueue = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean inboundDraining = new AtomicBoolean(false);

    public NettyWsTransportSession(Channel channel, String clientIp, String browserSessionToken) {
        this.channel = channel;
        this.id = channel.id().asLongText();
        this.clientIp = clientIp;
        this.browserSessionToken = browserSessionToken;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String clientIp() {
        return clientIp;
    }

    @Override
    public String browserSessionToken() {
        return browserSessionToken;
    }

    @Override
    public boolean isOpen() {
        return !closed.get() && channel.isActive();
    }

    @Override
    public boolean sendText(String payload) {
        if (!isOpen() || !channel.isWritable()) {
            return false;
        }
        channel.writeAndFlush(new TextWebSocketFrame(payload), channel.voidPromise());
        return true;
    }

    @Override
    public boolean sendBatch(List<String> payloads) {
        if (payloads == null || payloads.isEmpty()) {
            return true;
        }
        if (!isOpen() || !channel.isWritable()) {
            return false;
        }
        if (payloads.size() == 1) {
            channel.writeAndFlush(new TextWebSocketFrame(payloads.getFirst()), channel.voidPromise());
            return true;
        }
        for (String payload : payloads) {
            channel.write(new TextWebSocketFrame(payload), channel.voidPromise());
        }
        channel.flush();
        return true;
    }

    @Override
    public boolean supportsZeroCopyFrames() {
        return true;
    }

    @Override
    public boolean sendFrames(List<ByteBuf> sharedFrames, List<String> fallbackPayloads) {
        if (sharedFrames == null || sharedFrames.isEmpty()) {
            return true;
        }
        if (!isOpen() || !channel.isWritable()) {
            return false;
        }
        if (sharedFrames.size() == 1) {
            channel.writeAndFlush(new TextWebSocketFrame(sharedFrames.getFirst().retainedDuplicate()), channel.voidPromise());
            return true;
        }
        for (ByteBuf frame : sharedFrames) {
            channel.write(new TextWebSocketFrame(frame.retainedDuplicate()), channel.voidPromise());
        }
        channel.flush();
        return true;
    }

    @Override
    public int queuedMessages() {
        return channel.isActive() && !channel.isWritable() ? 1 : 0;
    }

    @Override
    public int queueCapacity() {
        return 1;
    }

    @Override
    public void close(WsCloseStatus status) {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        WsCloseStatus effective = status == null ? WsCloseStatus.NORMAL : status;
        if (channel.isActive()) {
            String reason = effective.reason() == null ? "" : effective.reason();
            channel.writeAndFlush(new CloseWebSocketFrame(effective.code(), reason))
                    .addListener(ChannelFutureListener.CLOSE);
        } else {
            channel.close();
        }
    }

    void markClosed() {
        closed.set(true);
    }

    /** Executes inbound tasks sequentially on a virtual thread without holding any idle threads. */
    void executeInbound(Runnable task) {
        inboundQueue.add(task);
        if (inboundDraining.compareAndSet(false, true)) {
            Thread.startVirtualThread(this::drainInbound);
        }
    }

    private void drainInbound() {
        try {
            while (true) {
                Runnable next;
                while ((next = inboundQueue.poll()) != null) {
                    try {
                        next.run();
                    } catch (Throwable ignored) {
                        // Individual task failures are handled/logged by ClientWebSocketHandler.
                    }
                }
                inboundDraining.set(false);
                if (inboundQueue.isEmpty() || !inboundDraining.compareAndSet(false, true)) {
                    return;
                }
            }
        } catch (Throwable failure) {
            inboundDraining.set(false);
            throw failure;
        }
    }
}
