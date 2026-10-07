package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class VeriffKycProvider implements KycProvider {
    private final GatewayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public VeriffKycProvider(GatewayProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }
    @Override public String code() { return "VERIFF"; }

    @Override public KycProviderSession start(long userId, KycSubmissionRequest request, List<KycDocument> documents) {
        var config = properties.getKyc();
        if (config.isSimulationEnabled()) return new KycProviderSession(code(), "SIM-VERIFF-" + userId + "-" + Instant.now().toEpochMilli(), null, null, "PENDING", true);
        var veriff = config.getVeriff();
        try {
            Map<String, Object> verification = new java.util.LinkedHashMap<>();
            verification.put("vendorData", Long.toString(userId));
            verification.put("endUserId", Long.toString(userId));
            verification.put("person", Map.of("nationality", request.country()));
            if (veriff.getCallbackUrl() != null && !veriff.getCallbackUrl().isBlank()) verification.put("callback", veriff.getCallbackUrl());
            byte[] payload = mapper.writeValueAsBytes(Map.of("verification", verification));
            HttpRequest req = HttpRequest.newBuilder(URI.create(veriff.getBaseUrl() + "/v1/sessions"))
                    .timeout(Duration.ofSeconds(15))
                    .header("X-AUTH-CLIENT", veriff.getApiKey()).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofByteArray(payload)).build();
            HttpResponse<byte[]> response = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) throw new IllegalStateException("Veriff returned HTTP " + response.statusCode());
            JsonNode root = mapper.readTree(response.body()).path("verification");
            String id = root.path("id").asString("");
            String url = root.path("url").asString("");
            if (id.isBlank() || url.isBlank()) throw new IllegalStateException("Veriff did not return a session id and URL");
            return new KycProviderSession(code(), id, null, url, "PENDING", false);
        } catch (Exception ex) { throw new IllegalStateException("Veriff session creation failed", ex); }
    }

    @Override public Optional<KycProviderResult> verifyCallback(Map<String, String> headers, byte[] body) {
        if (properties.getKyc().isSimulationEnabled()) return Optional.empty();
        var conf = properties.getKyc().getVeriff();
        String client = SumsubKycProvider.header(headers, "x-auth-client");
        String signature = SumsubKycProvider.header(headers, "x-hmac-signature");
        if (!SumsubKycProvider.constantTimeEquals(client, conf.getApiKey())
                || !SumsubKycProvider.constantTimeEquals(signature, SumsubKycProvider.hmac(conf.getSharedSecret(), "HmacSHA256", body))) {
            throw new IllegalArgumentException("invalid Veriff webhook signature");
        }
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode verification = root.path("verification");
            String providerStatus = verification.path("status").asString(root.path("status").asString(""));
            String status = switch (providerStatus.toLowerCase()) {
                case "approved" -> "VERIFIED";
                case "declined" -> "REJECTED";
                default -> "PENDING";
            };
            String reference = verification.path("id").asString("");
            String user = verification.path("vendorData").asString(verification.path("endUserId").asString(""));
            String reason = root.path("reason").asString("");
            return Optional.of(new KycProviderResult(reference, user, SumsubKycProvider.sha256(body), status,
                    reason.isBlank() ? null : reason));
        } catch (Exception ex) { throw new IllegalArgumentException("invalid Veriff webhook body", ex); }
    }
}
