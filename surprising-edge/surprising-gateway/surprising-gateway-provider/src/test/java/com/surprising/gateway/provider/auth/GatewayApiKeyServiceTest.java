package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.junit.jupiter.api.Test;

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
}
