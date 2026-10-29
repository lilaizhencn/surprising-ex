package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.interceptor.ProductRequestBodyFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.mock.web.*;
import tools.jackson.databind.ObjectMapper;

class GatewayInputLimitsTest {
    @Test void applicationMapperBoundsDepthNumbersAndStrings() {
        new ApplicationContextRunner().withConfiguration(AutoConfigurations.of(JacksonAutoConfiguration.class))
                .withUserConfiguration(GatewayJsonConfiguration.class).run(context -> {
                    var json = context.getBean(ObjectMapper.class);
                    assertThat(json.readTree("{\"quantity\":9223372036854775807}").path("quantity").asLong()).isEqualTo(Long.MAX_VALUE);
                    for (String input : java.util.List.of("[".repeat(65) + "0" + "]".repeat(65), "1".repeat(65), "\"" + "x".repeat(262145) + "\""))
                        assertThatThrownBy(() -> json.readTree(input)).isInstanceOf(tools.jackson.core.exc.StreamConstraintsException.class);
                });
    }

    @Test void rejectsOversizedOrdinaryBodiesBeforeBindingAndPreservesExactSignedBytes() throws Exception {
        var filter = new ProductRequestBodyFilter();
        var request = new MockHttpServletRequest("POST", "/api/v1/auth/login"); request.setContentType("application/json"); request.setContent(new byte[1024 * 1024 + 1]);
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain);
        assertThat(response.getStatus()).isEqualTo(413); assertThat(chain.getRequest()).isNull();
        byte[] body = "{ \"quantity\":123, \"symbol\":\"BTC-USDT\" }".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        request = new MockHttpServletRequest("POST", "/api/v1/gateway/trading"); request.setContentType("application/json"); request.setContent(body);
        response = new MockHttpServletResponse(); chain = new MockFilterChain(); filter.doFilter(request, response, chain);
        assertThat(chain.getRequest().getInputStream().readAllBytes()).isEqualTo(body);
        assertThat(chain.getRequest().getAttribute(ProductRequestBodyFilter.BODY)).isEqualTo(body);
    }
}
