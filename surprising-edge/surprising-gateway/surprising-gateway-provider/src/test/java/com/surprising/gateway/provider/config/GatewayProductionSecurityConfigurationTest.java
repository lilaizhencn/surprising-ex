package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import org.junit.jupiter.api.Test;

class GatewayProductionSecurityConfigurationTest {

    @Test
    void localProfileKeepsLocalDefaultsAvailableForDevelopment() {
        GatewayProperties properties = new GatewayProperties();

        assertThatCode(properties::validateProductionSecurityConfiguration)
                .doesNotThrowAnyException();
    }

    @Test
    void productionProfileRejectsDevelopmentDefaultsAndMissingDependencies() {
        GatewayProperties properties = new GatewayProperties();
        properties.setDeploymentProfile("production");

        assertThatThrownBy(properties::validateProductionSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.jwt-secret")
                .hasMessageContaining("custody-wallet.enabled")
                .hasMessageContaining("kyc-documents.enabled");
    }

    @Test
    void productionProfileAcceptsExplicitSecureConfiguration() {
        GatewayProperties properties = new GatewayProperties();
        properties.setDeploymentProfile("production");

        GatewayProperties.Security security = properties.getSecurity();
        security.setAllowUserIdHeaderFallback(false);
        security.setRequireAdminMfa(true);
        security.setAdminIpAllowlist(List.of("10.0.0.0/8"));
        security.setJwtSecret("jwt-secret-with-at-least-thirty-two-characters");
        security.setVerificationCodePepper("verification-pepper-with-at-least-thirty-two-characters");
        security.setMfaSecretEncryptionKey("mfa-encryption-key-with-at-least-thirty-two-characters");
        security.setResendApiKey("re_test_api_key");
        security.setResendFrom("security@example.com");

        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        wallet.setEnabled(true);
        wallet.setBaseUrl("https://wallet.example.com");
        wallet.setApiKey("wallet-key");
        wallet.setApiSecret("wallet-secret");
        wallet.setWebhookSecret("wallet-webhook-secret");
        wallet.setSpotAccountBaseUrl("https://account.example.com");

        GatewayProperties.KycDocuments documents = properties.getKycDocuments();
        documents.setEnabled(true);
        documents.setType("s3");
        documents.setEndpoint("https://s3.example.com");
        documents.setBucket("kyc-documents");
        documents.setAccessKey("s3-access-key");
        documents.setSecretKey("s3-secret-key");

        assertThatCode(properties::validateProductionSecurityConfiguration)
                .doesNotThrowAnyException();
    }
}
