package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;

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
        security.setTrustedProxyIpAllowlist(List.of("10.0.0.0/8"));
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

    @Test
    void activeSpringProductionProfileCannotBeDisabledByPropertyOverride() {
        GatewayProperties properties = new GatewayProperties();
        properties.setDeploymentProfile("local");
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        properties.setEnvironment(environment);

        assertThatThrownBy(properties::validateProductionSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("deployment-profile must remain production");
    }

    @Test
    void productionYamlBindsTheFailClosedSecurityBoundary() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        YamlPropertySourceLoader loader = new YamlPropertySourceLoader();
        for (var source : loader.load("production", new ClassPathResource("application-production.yml"))) {
            environment.getPropertySources().addLast(source);
        }

        GatewayProperties properties = Binder.get(environment)
                .bind("surprising.gateway", Bindable.of(GatewayProperties.class))
                .orElseThrow(() -> new IllegalStateException("surprising.gateway properties not bound"));

        assertThat(properties.getDeploymentProfile()).isEqualTo("production");
        assertThat(properties.getSecurity().isRequireIdentityForPrivateRoutes()).isTrue();
        assertThat(properties.getSecurity().isAllowUserIdHeaderFallback()).isFalse();
        assertThat(properties.getSecurity().isRequireAdminMfa()).isTrue();
        assertThat(properties.getSecurity().getTrustedProxyIpAllowlist()).containsExactly(
                "${GATEWAY_ADMIN_TRUSTED_PROXY_IP_ALLOWLIST}");
        assertThat(properties.getCustodyWallet().isEnabled()).isTrue();
        assertThat(properties.getKycDocuments().isEnabled()).isTrue();
        assertThat(properties.getKycDocuments().getType()).isEqualTo("s3");
    }
}
