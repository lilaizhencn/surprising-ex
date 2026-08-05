package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import java.util.Map;
import java.util.List;

class GatewayApiKeyServiceTest {

    private final GatewayApiKeyService service = new GatewayApiKeyService(
            null, null, new TotpService(new GatewayProperties()), null);

    @Test
    void removesOnlySignatureFromBinanceCanonicalQuery() {
        assertThat(service.canonicalQuery("symbol=BTCUSDT&timestamp=123&signature=abc&recvWindow=5000"))
                .isEqualTo("symbol=BTCUSDT&timestamp=123&recvWindow=5000");
    }

    @Test
    void producesLowercaseHexHmacSignature() {
        assertThat(service.sign("secret", "timestamp=123"))
                .isEqualTo("529760a2684af7ea9530e633ceedba2fbb63f4d9247b1507c3a89cbff9de3239");
    }

    @Test
    void bindsApiSignatureToMethodPathAndAllParameters() {
        assertThat(service.canonicalRequest("POST", "/sapi/v1/capital/withdraw/apply", Map.of(
                "coin", new String[]{"USDT"},
                "amount", new String[]{"1.25"},
                "signature", new String[]{"ignored"},
                "timestamp", new String[]{"123"})))
                .isEqualTo("POST\n/sapi/v1/capital/withdraw/apply\namount=1.25&coin=USDT&timestamp=123");
    }

    @Test
    void normalizesAndValidatesIpAllowlist() {
        assertThat(service.normalizeIpAllowlist(List.of("203.0.113.10", "203.0.113.0/24", "203.0.113.10")))
                .isEqualTo("203.0.113.10,203.0.113.0/24");
    }

    @Test
    void rejectsHostNamesInIpAllowlist() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> service.normalizeIpAllowlist(List.of("api.example.com")))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
