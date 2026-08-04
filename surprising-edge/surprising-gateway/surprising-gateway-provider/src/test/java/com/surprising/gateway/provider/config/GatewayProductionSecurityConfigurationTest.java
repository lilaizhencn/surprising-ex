package com.surprising.gateway.provider.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.MapPropertySource;
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
    void productionProfileRejectsOpenNetworkAllowlist() {
        GatewayProperties properties = new GatewayProperties();
        properties.setDeploymentProfile("production");
        properties.getSecurity().setAdminIpAllowlist(List.of("0.0.0.0/0"));
        properties.getSecurity().setTrustedProxyIpAllowlist(List.of("192.0.2.0/24"));

        assertThatThrownBy(properties::validateProductionSecurityConfiguration)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("security.admin-ip-allowlist must not allow all addresses");
    }

    @Test
    void productionYamlBindsTheFailClosedSecurityBoundary() throws IOException {
        StandardEnvironment environment = new StandardEnvironment();
        environment.setActiveProfiles("production");
        Map<String, Object> testEnvironment = new HashMap<>();
        testEnvironment.put("GATEWAY_ADMIN_IP_ALLOWLIST", "10.0.0.0/8");
        testEnvironment.put("GATEWAY_ADMIN_TRUSTED_PROXY_IP_ALLOWLIST", "192.0.2.0/24");
        testEnvironment.put("GATEWAY_JWT_SECRET", "jwt-secret-with-at-least-thirty-two-characters");
        testEnvironment.put("GATEWAY_VERIFICATION_CODE_PEPPER",
                "verification-pepper-with-at-least-thirty-two-characters");
        testEnvironment.put("GATEWAY_MFA_SECRET_ENCRYPTION_KEY",
                "mfa-encryption-key-with-at-least-thirty-two-characters");
        testEnvironment.put("RESEND_API_KEY", "re_test_api_key");
        testEnvironment.put("RESEND_FROM", "security@example.com");
        testEnvironment.put("GATEWAY_CUSTODY_WALLET_BASE_URL", "https://wallet.example.com");
        testEnvironment.put("GATEWAY_CUSTODY_WALLET_API_KEY", "wallet-key");
        testEnvironment.put("GATEWAY_CUSTODY_WALLET_API_SECRET", "wallet-secret");
        testEnvironment.put("GATEWAY_CUSTODY_WALLET_WEBHOOK_SECRET", "wallet-webhook-secret");
        testEnvironment.put("GATEWAY_SPOT_ACCOUNT_BASE_URL", "https://account.example.com");
        testEnvironment.put("GATEWAY_KYC_DOCUMENTS_ENDPOINT", "https://s3.example.com");
        testEnvironment.put("GATEWAY_KYC_DOCUMENTS_BUCKET", "kyc-documents");
        testEnvironment.put("GATEWAY_KYC_DOCUMENTS_REGION", "ap-southeast-1");
        testEnvironment.put("GATEWAY_KYC_DOCUMENTS_ACCESS_KEY", "s3-access-key");
        testEnvironment.put("GATEWAY_KYC_DOCUMENTS_SECRET_KEY", "s3-secret-key");
        environment.getPropertySources().addFirst(new MapPropertySource("test-env", testEnvironment));
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
        assertThat(properties.getSecurity().getAdminIpAllowlist()).containsExactly("10.0.0.0/8");
        assertThat(properties.getSecurity().getTrustedProxyIpAllowlist()).containsExactly("192.0.2.0/24");
        assertThat(properties.getCustodyWallet().isEnabled()).isTrue();
        assertThat(properties.getKycDocuments().isEnabled()).isTrue();
        assertThat(properties.getKycDocuments().getType()).isEqualTo("s3");
        properties.setEnvironment(environment);
        assertThatCode(properties::validateProductionSecurityConfiguration)
                .doesNotThrowAnyException();
    }
}
