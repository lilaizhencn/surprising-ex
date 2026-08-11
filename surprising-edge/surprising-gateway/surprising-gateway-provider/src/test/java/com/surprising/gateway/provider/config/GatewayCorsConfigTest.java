package com.surprising.gateway.provider.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.DefaultCorsProcessor;
import org.springframework.web.servlet.config.annotation.CorsRegistry;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayCorsConfigTest {

    @Test
    void productionWebOriginReceivesCorsHeaders() throws Exception {
        ExposedCorsRegistry registry = new ExposedCorsRegistry();
        new GatewayCorsConfig().addCorsMappings(registry);
        CorsConfiguration configuration = registry.configurations().get("/api/v1/**");
        MockHttpServletRequest request = new MockHttpServletRequest("OPTIONS", "/api/v1/cors-probe");
        request.addHeader("Origin", "https://ex.tokdou.com");
        request.addHeader("Access-Control-Request-Method", "GET");
        request.addHeader("Access-Control-Request-Headers", "authorization,x-product-line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        assertThat(new DefaultCorsProcessor().processRequest(configuration, request, response)).isTrue();
        assertThat(response.getHeader("Access-Control-Allow-Origin")).isEqualTo("https://ex.tokdou.com");
    }

    private static final class ExposedCorsRegistry extends CorsRegistry {

        private java.util.Map<String, CorsConfiguration> configurations() {
            return getCorsConfigurations();
        }
    }
}
