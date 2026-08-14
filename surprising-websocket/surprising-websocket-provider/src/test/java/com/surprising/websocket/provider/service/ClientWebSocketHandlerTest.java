package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.WebSocketSession;

class ClientWebSocketHandlerTest {

    @Test
    void authenticatesTrustedHandshakeHeaderBeforeQueryFallback() {
        WebSocketProperties properties = properties(true);
        WebSocketSession session = session("7001", "7002");

        assertThat(handler(properties).authenticatedUserId(session)).isEqualTo(7001L);
    }

    @Test
    void authenticatesQueryUserIdOnlyWhenFallbackIsEnabled() {
        assertThat(handler(properties(true)).authenticatedUserId(session(null, "7002"))).isEqualTo(7002L);
        assertThat(handler(properties(false)).authenticatedUserId(session(null, "7002"))).isNull();
    }

    @Test
    void rejectsInvalidAuthenticatedUserId() {
        assertThatThrownBy(() -> handler(properties(true)).authenticatedUserId(session(null, "0")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    private static ClientWebSocketHandler handler(WebSocketProperties properties) {
        return new ClientWebSocketHandler(null, null, properties, null);
    }

    private static WebSocketProperties properties(boolean queryFallback) {
        WebSocketProperties properties = new WebSocketProperties();
        properties.getSecurity().setAllowQueryUserIdFallback(queryFallback);
        return properties;
    }

    private static WebSocketSession session(String headerUserId, String queryUserId) {
        WebSocketSession session = mock(WebSocketSession.class);
        HttpHeaders headers = new HttpHeaders();
        if (headerUserId != null) {
            headers.set("X-User-Id", headerUserId);
        }
        when(session.getHandshakeHeaders()).thenReturn(headers);
        when(session.getUri()).thenReturn(URI.create("ws://localhost/ws/v1"
                + (queryUserId == null ? "" : "?userId=" + queryUserId)));
        return session;
    }
}
