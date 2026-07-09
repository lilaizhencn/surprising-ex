package com.surprising.websocket.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.surprising.websocket.api.model.SubscriptionTopic;
import com.surprising.websocket.api.model.WsChannel;
import com.surprising.websocket.provider.config.WebSocketProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.ObjectMapper;

class AdminWebSocketMetricsControllerTest {

    @Test
    void metricsRequiresAdminHeader() {
        AdminWebSocketMetricsController controller = new AdminWebSocketMetricsController(
                new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties()));

        assertThatThrownBy(() -> controller.metrics(null, null))
                .isInstanceOf(ResponseStatusException.class)
                .hasMessageContaining("admin identity header is required");
    }

    @Test
    void metricsReturnsRegistrySnapshot() {
        SubscriptionRegistry registry = new SubscriptionRegistry(new ObjectMapper(), new WebSocketProperties());
        ClientConnection connection = mock(ClientConnection.class);
        when(connection.id()).thenReturn("s-1");
        when(connection.authenticatedUserId()).thenReturn(1001L);
        registry.add(connection);
        registry.subscribe(connection, new SubscriptionTopic(WsChannel.POSITIONS, "BTC-USDT", null, 1001L));

        var response = new AdminWebSocketMetricsController(registry).metrics("7", "admin");

        assertThat(response.activeConnections()).isEqualTo(1);
        assertThat(response.authenticatedConnections()).isEqualTo(1);
        assertThat(response.totalSubscriptions()).isEqualTo(1);
        assertThat(response.channels()).hasSize(1);
        assertThat(response.channels().get(0).channel()).isEqualTo("POSITIONS");
    }
}
