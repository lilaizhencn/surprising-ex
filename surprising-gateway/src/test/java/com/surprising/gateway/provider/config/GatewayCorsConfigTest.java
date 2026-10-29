package com.surprising.gateway.provider.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import com.surprising.websocket.provider.config.WebSocketProperties;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCorsConfigTest {

    @Test
    void productionWebOriginReceivesCorsHeaders() throws Exception {
        try (var context = new AnnotationConfigApplicationContext()) {
        context.registerBean(GatewayProperties.class, GatewayProperties::new);
        context.registerBean(WebSocketProperties.class, WebSocketProperties::new);
        context.register(GatewayCorsConfig.class); context.refresh();
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/cors-probe");
        request.addHeader("Origin", "https://ex.tokdou.com");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "authorization,x-product-line");
        MockHttpServletResponse response = new MockHttpServletResponse();
        CorsConfiguration configuration = context.getBean("gatewayCorsSource", CorsConfigurationSource.class).getCorsConfiguration(request);

        assertThat(new DefaultCorsProcessor().processRequest(configuration, request, response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://ex.tokdou.com");
        assertThat(response.getHeader("Access-Control-Expose-Headers")).contains("Retry-After");
        }
    }
}
