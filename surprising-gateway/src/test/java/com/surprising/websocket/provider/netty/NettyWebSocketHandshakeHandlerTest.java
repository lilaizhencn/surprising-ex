package com.surprising.websocket.provider.netty;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.auth.utils.ClientIpResolver;
import com.surprising.gateway.provider.config.GatewayProperties;
import com.surprising.websocket.api.WebSocketApiPaths;
import com.surprising.websocket.provider.config.WebSocketProperties;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.handler.codec.http.DefaultFullHttpRequest;
import io.netty.handler.codec.http.FullHttpResponse;
import io.netty.handler.codec.http.HttpHeaderNames;
import io.netty.handler.codec.http.HttpMethod;
import io.netty.handler.codec.http.HttpResponseStatus;
import io.netty.handler.codec.http.HttpVersion;
import java.util.List;
import org.junit.jupiter.api.Test;

class NettyWebSocketHandshakeHandlerTest {

    @Test
    void configuredOriginsAreEnforcedOnHandshake() {
        var allowed = handshake(List.of("https://app.example.com", "https://m.example.com"), "https://app.example.com");
        assertThat((Object) allowed.readInbound()).isNotNull();
        assertThat(allowed.isActive()).isTrue();
        allowed.finishAndReleaseAll();

        var rejected = handshake(List.of("https://app.example.com"), "https://evil.example.com");
        FullHttpResponse response = rejected.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        response.release();
        assertThat(rejected.isActive()).isFalse();
    }

    @Test
    void emptyOriginsDoNotImplicitlyAllowEveryWebsite() {
        var channel = handshake(List.of(), "https://app.example.com");
        FullHttpResponse response = channel.readOutbound();
        assertThat(response.status()).isEqualTo(HttpResponseStatus.FORBIDDEN);
        response.release();
        assertThat(channel.isActive()).isFalse();
    }

    private EmbeddedChannel handshake(List<String> origins, String originHeader) {
        var properties = new WebSocketProperties();
        properties.getSecurity().setAllowedOrigins(origins);
        var gateway = new GatewayProperties();
        gateway.getSecurity().setTrustedProxyIpAllowlist(List.of());
        var resolver = new ClientIpResolver(gateway);
        var channel = new EmbeddedChannel(new NettyWebSocketHandshakeHandler(properties, resolver)) {
            @Override
            public java.net.SocketAddress remoteAddress() {
                return new java.net.InetSocketAddress("198.51.100.1", 12345);
            }
        };
        var request = new DefaultFullHttpRequest(HttpVersion.HTTP_1_1, HttpMethod.GET, WebSocketApiPaths.WS_V1);
        request.headers().set(HttpHeaderNames.ORIGIN, originHeader);
        request.headers().set("X-Forwarded-For", "198.51.100.1");
        channel.writeInbound(request);
        return channel;
    }
}
