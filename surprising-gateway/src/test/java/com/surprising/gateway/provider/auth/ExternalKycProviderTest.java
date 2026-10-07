package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ExternalKycProviderTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void simulatedSumsubAndVeriffSessionsNeverCallProviders() {
        GatewayProperties properties = new GatewayProperties();
        properties.getKyc().setSimulationEnabled(true);
        KycSubmissionRequest request = request("SUMSUB");
        var sumsub = new SumsubKycProvider(properties, mapper).start(42, request, List.of());
        var veriff = new VeriffKycProvider(properties, mapper).start(42, request, List.of());
        assertThat(sumsub.provider()).isEqualTo("SUMSUB");
        assertThat(sumsub.simulated()).isTrue();
        assertThat(sumsub.providerReference()).startsWith("SIM-SUMSUB-");
        assertThat(veriff.provider()).isEqualTo("VERIFF");
        assertThat(veriff.simulated()).isTrue();
        assertThat(veriff.providerReference()).startsWith("SIM-VERIFF-");
    }

    @Test
    void registrySelectsTheServerConfiguredProviderInsteadOfClientProviderField() {
        GatewayProperties properties = new GatewayProperties();
        properties.getKyc().setProvider("VERIFF");
        properties.getKyc().setSimulationEnabled(true);
        var registry = new KycProviderRegistry(List.of(
                new SumsubKycProvider(properties, mapper), new VeriffKycProvider(properties, mapper)), properties);
        var session = registry.start(42, request("SUMSUB"), List.of());
        assertThat(session.provider()).isEqualTo("VERIFF");
    }

    @Test
    void registryRefreshesTheProviderStoredOnAnExistingSessionAfterGlobalSwitch() {
        GatewayProperties properties = new GatewayProperties();
        properties.getKyc().setProvider("SUMSUB");
        properties.getKyc().setSimulationEnabled(false);
        KycProvider sumsub = provider("SUMSUB");
        KycProvider veriff = provider("VERIFF");
        var registry = new KycProviderRegistry(List.of(sumsub, veriff), properties);

        var refreshed = registry.refreshSession("VERIFF", 42, "existing-veriff-session");

        assertThat(refreshed.provider()).isEqualTo("VERIFF");
        assertThat(refreshed.providerReference()).isEqualTo("existing-veriff-session");
    }

    @Test
    void sumsubCallbackRequiresValidRawBodyHmacAndMapsManualReviewAsPending() {
        GatewayProperties properties = new GatewayProperties();
        properties.getKyc().setSimulationEnabled(false);
        properties.getKyc().getSumsub().setWebhookSecret("a-test-webhook-secret-with-at-least-32-chars");
        byte[] body = "{\"type\":\"applicantReviewed\",\"reviewStatus\":\"onHold\",\"applicantId\":\"app-1\",\"externalUserId\":\"42\"}"
                .getBytes(StandardCharsets.UTF_8);
        String digest = SumsubKycProvider.hmac(properties.getKyc().getSumsub().getWebhookSecret(), "HmacSHA256", body);
        var result = new SumsubKycProvider(properties, mapper)
                .verifyCallback(Map.of("x-payload-digest", digest), body).orElseThrow();
        assertThat(result.externalUserId()).isEqualTo("42");
        assertThat(result.providerReference()).isEqualTo("app-1");
        assertThat(result.status()).isEqualTo("PENDING");
        assertThatThrownBy(() -> new SumsubKycProvider(properties, mapper)
                .verifyCallback(Map.of("x-payload-digest", "bad"), body))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("signature");
    }

    @Test
    void veriffDecisionWebhookIsAuthenticatedAndMapsApproved() {
        GatewayProperties properties = new GatewayProperties();
        properties.getKyc().setSimulationEnabled(false);
        properties.getKyc().getVeriff().setApiKey("veriff-api-key");
        properties.getKyc().getVeriff().setSharedSecret("veriff-shared-secret-with-at-least-32-characters");
        byte[] body = "{\"status\":\"success\",\"verification\":{\"id\":\"session-1\",\"status\":\"approved\",\"vendorData\":\"42\"}}"
                .getBytes(StandardCharsets.UTF_8);
        String signature = SumsubKycProvider.hmac(properties.getKyc().getVeriff().getSharedSecret(), "HmacSHA256", body);
        var result = new VeriffKycProvider(properties, mapper).verifyCallback(Map.of(
                "X-AUTH-CLIENT", "veriff-api-key", "X-HMAC-SIGNATURE", signature), body).orElseThrow();
        assertThat(result.status()).isEqualTo("VERIFIED");
        assertThat(result.externalUserId()).isEqualTo("42");
        assertThat(result.providerReference()).isEqualTo("session-1");
        assertThatThrownBy(() -> new VeriffKycProvider(properties, mapper).verifyCallback(Map.of(
                "X-AUTH-CLIENT", "wrong", "X-HMAC-SIGNATURE", signature), body))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("signature");
    }

    private static KycSubmissionRequest request(String provider) {
        return new KycSubmissionRequest("INDIVIDUAL", "STANDARD", "SG", "PASSPORT", provider,
                null, null, "PENDING", List.of());
    }

    private static KycProvider provider(String code) {
        return new KycProvider() {
            @Override public String code() { return code; }
            @Override public KycProviderSession start(long userId, KycSubmissionRequest request,
                                                       List<com.surprising.gateway.provider.auth.ComplianceModels.KycDocument> documents) {
                return new KycProviderSession(code, "new-session", null, null, "PENDING", false);
            }
            @Override public KycProviderSession refreshSession(long userId, String providerReference) {
                return new KycProviderSession(code, providerReference, "refreshed-token", null, "PENDING", false);
            }
        };
    }
}
