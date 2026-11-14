package com.surprising.websocket.provider.netty;

import com.surprising.websocket.provider.handler.ClientWebSocketHandler;
import com.surprising.websocket.provider.session.WsCloseStatus;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.netty.util.AttributeKey;
import lombok.extern.slf4j.Slf4j;

/**
 * Bridges Netty WebSocket lifecycle and text frames to {@link ClientWebSocketHandler}.
 */
@Slf4j
@ChannelHandler.Sharable
final class NettyWebSocketChannelHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final AttributeKey<NettyWsTransportSession> ATTR_SESSION =
            AttributeKey.valueOf("surprising.ws.session");

    private final ClientWebSocketHandler delegate;

    NettyWebSocketChannelHandler(ClientWebSocketHandler delegate) {
        this.delegate = delegate;
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof WebSocketServerProtocolHandler.HandshakeComplete) {
            String clientIp = ctx.channel().attr(NettyWebSocketHandshakeHandler.ATTR_CLIENT_IP).get();
            String browserToken = ctx.channel().attr(NettyWebSocketHandshakeHandler.ATTR_BROWSER_TOKEN).get();
            var session = new NettyWsTransportSession(ctx.channel(), clientIp, browserToken);
            ctx.channel().attr(ATTR_SESSION).set(session);
            session.executeInbound(() -> {
                try {
                    delegate.afterConnectionEstablished(session);
                } catch (Exception ex) {
                    log.warn("ws.handshake.init_failed session={} error={}", session.id(), ex.getMessage());
                    session.close(WsCloseStatus.SERVER_ERROR);
                }
            });
            return;
        }
        if (evt instanceof IdleStateEvent idle && idle.state() == IdleState.READER_IDLE) {
            NettyWsTransportSession session = ctx.channel().attr(ATTR_SESSION).get();
            if (session != null) {
                session.close(WsCloseStatus.GOING_AWAY.withReason("websocket idle timeout"));
            } else {
                ctx.close();
            }
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        NettyWsTransportSession session = ctx.channel().attr(ATTR_SESSION).get();
        if (session == null) {
            return;
        }
        String payload = frame.text();
        session.executeInbound(() -> delegate.handleTextMessage(session, payload));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        NettyWsTransportSession session = ctx.channel().attr(ATTR_SESSION).getAndSet(null);
        if (session != null) {
            session.markClosed();
            session.executeInbound(() -> delegate.afterConnectionClosed(session, WsCloseStatus.NORMAL));
        }
        super.channelInactive(ctx);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        log.debug("netty websocket channel error: {}", cause.getMessage());
        ctx.close();
    }
}
