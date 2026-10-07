package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Component
public class SumsubKycProvider implements KycProvider {
    private final GatewayProperties properties;
    private final ObjectMapper mapper;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    public SumsubKycProvider(GatewayProperties properties, ObjectMapper mapper) { this.properties = properties; this.mapper = mapper; }
    @Override public String code() { return "SUMSUB"; }

    @Override public KycProviderSession start(long userId, KycSubmissionRequest request, List<KycDocument> documents) {
        var config = properties.getKyc();
        if (config.isSimulationEnabled()) return new KycProviderSession(code(), "SIM-SUMSUB-" + userId + "-" + Instant.now().toEpochMilli(), null, null, "PENDING", true);
        var sumsub = config.getSumsub();
        try {
            String externalUserId = Long.toString(userId);
            String existingPath = "/resources/applicants/-/byExternalUserId/" + enc(externalUserId);
            JsonNode existing = request("GET", existingPath, "");
            String applicantId = existing.path("id").asString("");
            if (applicantId.isBlank()) {
                String applicant = requestBody(Map.of("externalUserId", externalUserId, "fixedInfo", Map.of("country", request.country())));
                JsonNode created = request("POST", "/resources/applicants?levelName=" + enc(sumsub.getLevelName()), applicant);
                applicantId = created.path("id").asString("");
            }
            if (applicantId.isBlank()) throw new IllegalStateException("Sumsub did not return an applicant id");
            String tokenBody = requestBody(Map.of("userId", externalUserId, "levelName", sumsub.getLevelName(), "ttlInSecs", 600));
            JsonNode token = request("POST", "/resources/accessTokens/sdk", tokenBody);
            String accessToken = token.path("token").asString("");
            if (accessToken.isBlank()) throw new IllegalStateException("Sumsub did not return an SDK token");
            return new KycProviderSession(code(), applicantId, accessToken, null, "PENDING", false);
        } catch (Exception ex) { throw new IllegalStateException("Sumsub session creation failed", ex); }
    }

    @Override public KycProviderSession refreshSession(long userId, String providerReference) {
        var config = properties.getKyc();
        if (config.isSimulationEnabled()) return new KycProviderSession(code(), providerReference, null, null, "PENDING", true);
        try {
            String body = requestBody(Map.of("userId", Long.toString(userId),
                    "levelName", config.getSumsub().getLevelName(), "ttlInSecs", 600));
            JsonNode token = request("POST", "/resources/accessTokens/sdk", body);
            String accessToken = token.path("token").asString("");
            if (accessToken.isBlank()) throw new IllegalStateException("Sumsub did not return an SDK token");
            return new KycProviderSession(code(), providerReference, accessToken, null, "PENDING", false);
        } catch (Exception ex) { throw new IllegalStateException("Sumsub session token refresh failed", ex); }
    }

    @Override public Optional<KycProviderResult> verifyCallback(Map<String, String> headers, byte[] body) {
        var config = properties.getKyc();
        if (config.isSimulationEnabled()) return Optional.empty();
        String digest = header(headers, "x-payload-digest");
        String alg = header(headers, "x-payload-digest-alg");
        if (alg.isBlank()) alg = "HMAC_SHA256_HEX";
        String javaAlg = switch (alg.toUpperCase()) { case "HMAC_SHA512_HEX" -> "HmacSHA512"; case "HMAC_SHA1_HEX" -> "HmacSHA1"; default -> "HmacSHA256"; };
        if (!List.of("HMAC_SHA256_HEX", "HMAC_SHA512_HEX", "HMAC_SHA1_HEX").contains(alg.toUpperCase())) {
            throw new IllegalArgumentException("unsupported Sumsub webhook digest algorithm");
        }
        if (!constantTimeEquals(digest, hmac(config.getSumsub().getWebhookSecret(), javaAlg, body))) throw new IllegalArgumentException("invalid Sumsub webhook signature");
        try {
            JsonNode root = mapper.readTree(body);
            String type = root.path("type").asString("");
            String reviewStatus = root.path("reviewStatus").asString("");
            String answer = root.path("reviewResult").path("reviewAnswer").asString("");
            String status = "PENDING";
            if ("applicantReviewed".equals(type) && "completed".equalsIgnoreCase(reviewStatus)) {
                if ("GREEN".equalsIgnoreCase(answer)) status = "VERIFIED";
                else if ("RED".equalsIgnoreCase(answer)) status = "REJECTED";
            }
            String id = root.path("applicantId").asString("");
            String external = root.path("externalUserId").asString("");
            String event = sha256(body);
            String reason = root.path("reviewResult").path("clientComment").asString("");
            return Optional.of(new KycProviderResult(id, external, event, status, reason.isBlank() ? null : reason));
        } catch (Exception ex) { throw new IllegalArgumentException("invalid Sumsub webhook body", ex); }
    }

    private JsonNode request(String method, String path, String body) throws Exception {
        var c = properties.getKyc().getSumsub();
        long ts = Instant.now().getEpochSecond();
        String uri = path;
        String signature = hmac(c.getSecretKey(), "HmacSHA256", (ts + method + uri + body).getBytes(StandardCharsets.UTF_8));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(c.getBaseUrl() + path)).timeout(Duration.ofSeconds(15))
                .header("X-App-Token", c.getAppToken()).header("X-App-Access-Ts", Long.toString(ts))
                .header("X-App-Access-Sig", signature).header("Content-Type", "application/json");
        HttpRequest req = "GET".equals(method) ? b.GET().build() : b.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
        HttpResponse<byte[]> res = http.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (res.statusCode() == 404 && "GET".equals(method)) return mapper.readTree("{}");
        if (res.statusCode() < 200 || res.statusCode() >= 300) throw new IllegalStateException("Sumsub returned HTTP " + res.statusCode());
        return mapper.readTree(res.body());
    }
    private String requestBody(Object value) { return mapper.writeValueAsString(value); }
    static String hmac(String secret, String algorithm, byte[] data) {
        try { Mac mac = Mac.getInstance(algorithm); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm)); return HexFormat.of().formatHex(mac.doFinal(data)); }
        catch (Exception ex) { throw new IllegalStateException("unable to calculate provider signature", ex); }
    }
    static boolean constantTimeEquals(String supplied, String expected) { return supplied != null && MessageDigest.isEqual(supplied.toLowerCase().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)); }
    static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(""); }
    static String sha256(byte[] body) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
