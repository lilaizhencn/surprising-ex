package com.surprising.gateway.provider.auth;

import static org.assertj.core.api.Assertions.*;
import com.surprising.gateway.provider.auth.service.PasswordHasher;
import com.surprising.gateway.provider.config.GatewayLimitProperties;
import org.junit.jupiter.api.Test;

class PasswordHasherTest {
    @Test void usesFreshSaltAndTheNewFixedCostWithoutLegacyVerification() {
        var hasher = new PasswordHasher(new GatewayLimitProperties());
        String first = hasher.hash("strong-password-2026");
        String second = hasher.hash("strong-password-2026");
        assertThat(first).startsWith("pbkdf2_sha256$600000$").isNotEqualTo(second);
        assertThat(hasher.matches("strong-password-2026", first)).isTrue();
        assertThat(hasher.matches("wrong-password", first)).isFalse();
        assertThat(hasher.matches("strong-password-2026", first.replace("600000", "210000"))).isFalse();
        assertThat(hasher.matches("strong-password-2026", first.replace("600000", "2147483647"))).isFalse();
        assertThat(hasher.matches("strong-password-2026", "pbkdf2_sha256$600000$invalid$invalid")).isFalse();
        assertThat(hasher.matches("strong-password-2026", null)).isFalse();
        assertThatThrownBy(() -> hasher.hash("x")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> hasher.hash("x".repeat(129))).isInstanceOf(IllegalArgumentException.class);
    }
}
