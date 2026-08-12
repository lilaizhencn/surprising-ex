package com.surprising.gateway.provider.auth;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ComplianceValidationTest {

    @Test
    void normalizesSupportedKycProviders() {
        assertThat(ComplianceValidation.provider("third_party")).isEqualTo("THIRD_PARTY");
        assertThat(ComplianceValidation.provider(null)).isEqualTo("SELF");
    }

    @Test
    void requiresReferenceForThirdPartyVerification() {
        assertThatThrownBy(() -> ComplianceValidation.providerReference("THIRD_PARTY", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("providerReference");
    }

    @Test
    void allowsSelfVerificationWithoutExternalReference() {
        assertThat(ComplianceValidation.providerReference("SELF", null)).isNull();
    }
}
