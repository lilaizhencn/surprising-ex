package com.surprising.gateway.provider.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.surprising.gateway.provider.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.web.client.RestTemplate;
import tools.jackson.databind.ObjectMapper;

class CustodyWalletClientTest {

    private final CustodyWalletClient client = new CustodyWalletClient(
            new GatewayProperties(), mock(RestTemplate.class), new ObjectMapper());

    @Test
    void buildsWalletCompatibleCanonicalRequestAndSignature() {
        String canonical = client.canonicalRequest(1_754_320_000L, "nonce-1234567890AB", "post",
                "/custody/api/v1/withdrawals", "{}".getBytes());

        assertThat(canonical).contains("1754320000\nnonce-1234567890AB\nPOST\n/custody/api/v1/withdrawals\n");
        assertThat(client.sign("secret", canonical)).isNotBlank();
    }

    @Test
    void rejectsInvalidWalletSubjectAndIdempotencyKey() {
        assertThatThrownBy(() -> client.subject(0L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> client.createWithdrawal(42L, java.util.Map.of(), "bad"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
    }
}
