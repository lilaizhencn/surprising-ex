package com.surprising.websocket.provider.netty;

import com.surprising.gateway.provider.auth.controller.BrowserSessionCookies;
import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.provider.config.WebSocketProperties;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.codec.http.DefaultFullHttpResponse;
import io.netty.handler.codec.http.FullHttpRequest;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import io.netty.util.AttributeKey;
import io.netty.util.ReferenceCountUtil;
import java.net.InetSocketAddress;
import java.util.List;
import org.springframework.util.PatternMatchUtils;

/**
 * Validates WebSocket upgrade path, Origin allowlist, client IP resolution, and browser session
 * cookies before {@link io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler}
 * completes the RFC 6455 handshake.
 */
final class NettyWebSocketHandshakeHandler extends ChannelInboundHandlerAdapter {

    static final AttributeKey<String> ATTR_CLIENT_IP = AttributeKey.valueOf("surprising.ws.clientIp");
    static final AttributeKey<String> ATTR_BROWSER_TOKEN = AttributeKey.valueOf("surprising.ws.browserToken");

    private final WebSocketProperties properties;
    private final ClientIpResolver clientIpResolver;

    NettyWebSocketHandshakeHandler(WebSocketProperties properties, ClientIpResolver clientIpResolver) {
        this.properties = properties;
        this.clientIpResolver = clientIpResolver;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!(msg instanceof FullHttpRequest request)) {
            ctx.fireChannelRead(msg);
            return;
        }
        String path = stripQuery(request.uri());
        if (!WebSocketApiPaths.WS_V1.equals(path)) {
            reject(ctx, request, HttpResponseStatus.NOT_FOUND);
            return;
        }
        if (!isOriginAllowed(request.headers().get(HttpHeaderNames.ORIGIN))) {
            reject(ctx, request, HttpResponseStatus.FORBIDDEN);
            return;
        }
        String remoteAddress = remoteIp(ctx);
        String clientIp = clientIpResolver.resolve(remoteAddress, request.headers()::get);
        if (clientIp == null || clientIp.isBlank()) {
            reject(ctx, request, HttpResponseStatus.FORBIDDEN);
            return;
        }
        ctx.channel().attr(ATTR_CLIENT_IP).set(clientIp);
        String browserToken = BrowserSessionCookies.anySessionToken(request.headers().get(HttpHeaderNames.COOKIE));
        if (browserToken != null) {
            ctx.channel().attr(ATTR_BROWSER_TOKEN).set(browserToken);
        }
        ctx.pipeline().remove(this);
        ctx.fireChannelRead(request);
    }

    private boolean isOriginAllowed(String origin) {
        List<String> allowed = properties.getSecurity().getAllowedOrigins();
        if (allowed == null || allowed.isEmpty()) {
            return false;
        }
        if (allowed.contains("*")) {
            return true;
        }
        if (origin == null || origin.isBlank()) {
            return false;
        }
        for (String pattern : allowed) {
            if (pattern != null && PatternMatchUtils.simpleMatch(pattern, origin)) {
                return true;
            }
        }
        return false;
    }

    private static String remoteIp(ChannelHandlerContext ctx) {
        if (ctx.channel().remoteAddress() instanceof InetSocketAddress inet && inet.getAddress() != null) {
            return inet.getAddress().getHostAddress();
        }
        return null;
    }

    private static String stripQuery(String uri) {
        if (uri == null) {
            return "";
        }
        int query = uri.indexOf('?');
        return query >= 0 ? uri.substring(0, query) : uri;
    }

    private static void reject(ChannelHandlerContext ctx, FullHttpRequest request, HttpResponseStatus status) {
        ReferenceCountUtil.release(request);
        var response = new DefaultFullHttpResponse(HttpVersion.HTTP_1_1, status, Unpooled.EMPTY_BUFFER);
        response.headers().setInt(HttpHeaderNames.CONTENT_LENGTH, 0);
        ctx.writeAndFlush(response).addListener(ChannelFutureListener.CLOSE);
    }
}
