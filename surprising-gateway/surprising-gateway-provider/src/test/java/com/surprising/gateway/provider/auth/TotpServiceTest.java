package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.assertThat;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class TotpServiceTest {

    @Test
    void generatedCodeVerifiesWithinCurrentTimeWindow() {
        TotpService service = new TotpService(new GatewayProperties());
        Instant now = Instant.parse("2026-07-02T00:00:00Z");
        String secret = service.newSecret();
        String code = service.code(secret, now);

        assertThat(code).matches("\\d{6}");
        assertThat(service.verify(secret, code, now)).isTrue();
        assertThat(service.verify(secret, "000000".equals(code) ? "111111" : "000000", now)).isFalse();
    }

    @Test
    void encryptedSecretRoundTripsAndProvisioningUriUsesTotpScheme() {
        GatewayProperties properties = new GatewayProperties();
        properties.getSecurity().setMfaSecretEncryptionKey("test-encryption-key");
        TotpService service = new TotpService(properties);
        String secret = service.newSecret();

        String encrypted = service.encryptSecret(secret);

        assertThat(encrypted).isNotEqualTo(secret);
        assertThat(service.decryptSecret(encrypted)).isEqualTo(secret);
        assertThat(service.provisioningUri("admin", secret))
                .startsWith("otpauth://totp/")
                .contains("secret=" + secret)
                .contains("digits=6")
                .contains("period=30");
    }
}
