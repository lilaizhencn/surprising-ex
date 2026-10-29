package com.surprising.gateway.provider.auth.client;

import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
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
        String levelName = sumsub.levelName(request.applicantType(), request.kycLevel());
        try {
            String externalUserId = externalUserId(userId,request.applicantType());
            String existingPath = "/resources/applicants/-;externalUserId=" + enc(externalUserId) + "/one";
            JsonNode existing = request("GET", existingPath, "");
            String applicantId = existing.path("id").asString("");
            if (applicantId.isBlank()) {
                String country = new java.util.Locale.Builder().setRegion(request.country()).build().getISO3Country();
                String applicant = requestBody(Map.of("externalUserId", externalUserId,
                        "type", "BUSINESS".equals(request.applicantType()) ? "company" : "individual",
                        "fixedInfo", "BUSINESS".equals(request.applicantType())
                                ? Map.of("companyInfo",Map.of("country",country)) : Map.of("country",country)));
                JsonNode created = request("POST", "/resources/applicants?levelName=" + enc(levelName), applicant);
                applicantId = created.path("id").asString("");
            } else {
                if ("FINAL".equals(existing.path("review").path("reviewResult").path("reviewRejectType").asString(""))) {
                    throw new IllegalArgumentException("the provider requires a compliance review before another application");
                }
                if (!levelName.equals(existing.path("review").path("levelName").asString(""))) {
                    request("POST", "/resources/applicants/" + enc(applicantId) + "/moveToLevel?name=" + enc(levelName), "{}");
                }
                request("POST", "/resources/applicants/" + enc(applicantId) + "/reset", "");
            }
            if (applicantId.isBlank()) throw new IllegalStateException("Sumsub did not return an applicant id");
            String tokenBody = requestBody(Map.of("userId", externalUserId, "levelName", levelName, "ttlInSecs", 600));
            JsonNode token = request("POST", "/resources/accessTokens/sdk", tokenBody);
            String accessToken = token.path("token").asString("");
            if (accessToken.isBlank()) throw new IllegalStateException("Sumsub did not return an SDK token");
            return new KycProviderSession(code(), applicantId, accessToken, null, "PENDING", false);
        } catch (IllegalArgumentException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalStateException("Sumsub session creation failed", ex); }
    }

    @Override public KycProviderSession refreshSession(KycProfile profile, String redirectUrl) {
        var config = properties.getKyc();
        if (config.isSimulationEnabled()) return new KycProviderSession(code(), profile.providerReference(), null, null, "PENDING", true);
        try {
            String body = requestBody(Map.of("userId", externalUserId(profile.userId(),profile.applicantType()),
                    "levelName", config.getSumsub().levelName(profile.applicantType(), profile.kycLevel()), "ttlInSecs", 600));
            JsonNode token = request("POST", "/resources/accessTokens/sdk", body);
            String accessToken = token.path("token").asString("");
            if (accessToken.isBlank()) throw new IllegalStateException("Sumsub did not return an SDK token");
            return new KycProviderSession(code(), profile.providerReference(), accessToken, null, "PENDING", false);
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
            String timestamp = root.path("createdAtMs").asString(root.path("createdAt").asString(""));
            Instant occurredAt = timestamp.contains("T") ? Instant.parse(timestamp)
                    : java.time.LocalDateTime.parse(timestamp, java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss[.SSS]")).toInstant(java.time.ZoneOffset.UTC);
            Instant evidenceExpiry = null;
            if ("VERIFIED".equals(status)) {
                final JsonNode applicant;
                try { applicant = request("GET", "/resources/applicants/" + enc(id) + "/one", ""); }
                catch (InterruptedException ex) { Thread.currentThread().interrupt(); throw new IllegalStateException("KYC evidence lookup was interrupted",ex); }
                catch (Exception ex) { throw new IllegalStateException("KYC evidence is temporarily unavailable",ex); }
                if (!id.equals(applicant.path("id").asString("")) || !external.equals(applicant.path("externalUserId").asString(""))) {
                    throw new IllegalArgumentException("Sumsub verification evidence does not match the applicant");
                }
                for (JsonNode document : applicant.path("info").path("idDocs")) {
                    if (!List.of("ID_CARD","PASSPORT","DRIVERS","RESIDENCE_PERMIT").contains(document.path("idDocType").asString(""))) continue;
                    String until = document.path("validUntil").asString("");
                    if (until.isBlank()) continue;
                    Instant end = java.time.LocalDate.parse(until).plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant();
                    if (evidenceExpiry == null || end.isBefore(evidenceExpiry)) evidenceExpiry = end;
                }
            }
            return Optional.of(new KycProviderResult(id, external, event, status, reason.isBlank() ? null : reason,
                    occurredAt, root.path("levelName").asString(""), evidenceExpiry));
        } catch (IllegalStateException ex) { throw ex; }
        catch (Exception ex) { throw new IllegalArgumentException("invalid Sumsub webhook body", ex); }
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
    private static String externalUserId(long userId,String applicantType) {
        return Long.toString(userId) + ("BUSINESS".equals(applicantType) ? ":BUSINESS" : "");
    }
    public static String hmac(String secret, String algorithm, byte[] data) {
        try { Mac mac = Mac.getInstance(algorithm); mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), algorithm)); return HexFormat.of().formatHex(mac.doFinal(data)); }
        catch (Exception ex) { throw new IllegalStateException("unable to calculate provider signature", ex); }
    }
    public static boolean constantTimeEquals(String supplied, String expected) { return supplied != null && MessageDigest.isEqual(supplied.toLowerCase().getBytes(StandardCharsets.UTF_8), expected.getBytes(StandardCharsets.UTF_8)); }
    public static String header(Map<String, String> headers, String name) { return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase(name)).map(Map.Entry::getValue).findFirst().orElse(""); }
    public static String sha256(byte[] body) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(body)); } catch (Exception ex) { throw new IllegalStateException(ex); } }
    private static String enc(String s) { return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8); }
}
