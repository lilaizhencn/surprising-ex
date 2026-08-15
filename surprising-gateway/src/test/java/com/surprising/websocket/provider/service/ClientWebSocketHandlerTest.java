package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.websocket.provider.config.WebSocketProperties;
import java.net.URI;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.web.socket.WebSocketSession;

class ClientWebSocketHandlerTest {

    @Test
    void authenticatesTrustedHandshakeHeader() {
        WebSocketProperties properties = properties();
        WebSocketSession session = session("7001", "7002");

        assertThat(handler(properties).authenticatedUserId(session)).isEqualTo(7001L);
    }

    @Test
    void doesNotReadUserIdFromQueryString() {
        assertThat(handler(properties()).authenticatedUserId(session(null, "7002"))).isNull();
    }

    @Test
    void rejectsInvalidAuthenticatedUserId() {
        assertThat(handler(properties()).authenticatedUserId(session(null, "0"))).isNull();
    }

    private static ClientWebSocketHandler handler(WebSocketProperties properties) {
        return new ClientWebSocketHandler(null, null, properties, null);
    }

    private static WebSocketProperties properties() {
        return new WebSocketProperties();
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
