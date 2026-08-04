package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.nio.charset.StandardCharsets;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.RestTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class CustodyWalletClient {

    private static final String API_PREFIX = "/custody/api/v1";
    private final GatewayProperties properties;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final SecureRandom random = new SecureRandom();

    public CustodyWalletClient(GatewayProperties properties,
                               RestTemplate restTemplate,
                               ObjectMapper objectMapper) {
        this.properties = properties;
        this.restTemplate = restTemplate;
        this.objectMapper = objectMapper;
    }

    public Map<String, Object> createAddress(long userId, String chain, Long addressVersion) {
        String path = API_PREFIX + "/addresses";
        Map<String, Object> body = Map.of(
                "chainId", requireText(chain, "chain"),
                "subject", subject(userId),
                "addressVersion", addressVersion == null ? 1L : addressVersion);
        return exchange(HttpMethod.POST, path, body, Map.class, null);
    }

    public List<Map<String, Object>> deposits(long userId, String chain, String asset, int limit) {
        String query = query(Map.of(
                "chain", optional(chain),
                "assetSymbol", optional(asset),
                "search", subject(userId),
                "limit", Integer.toString(Math.max(1, Math.min(limit, 200))),
                "offset", "0"));
        return exchange(HttpMethod.GET, API_PREFIX + "/deposits" + query, null,
                new ParameterizedTypeReference<>() {}, null);
    }

    public List<Map<String, Object>> withdrawals(long userId, String chain, String asset, int limit) {
        String query = query(Map.of(
                "chain", optional(chain),
                "assetSymbol", optional(asset),
                "search", subject(userId),
                "limit", Integer.toString(Math.max(1, Math.min(limit, 200))),
                "offset", "0"));
        return exchange(HttpMethod.GET, API_PREFIX + "/withdrawals" + query, null,
                new ParameterizedTypeReference<>() {}, null);
    }

    public Map<String, Object> createWithdrawal(long userId,
                                                 Map<String, Object> withdrawal,
                                                 String idempotencyKey) {
        if (idempotencyKey == null || !idempotencyKey.matches("[A-Za-z0-9._:-]{8,128}")) {
            throw new IllegalArgumentException("Idempotency-Key must be 8-128 safe characters");
        }
        return exchange(HttpMethod.POST, API_PREFIX + "/withdrawals", withdrawal, Map.class, idempotencyKey);
    }

    public long amountUnits(String asset, String amount) {
        if (asset == null || amount == null || amount.isBlank()) {
            throw new IllegalArgumentException("asset and amount are required");
        }
        Long scale = properties.getCustodyWallet().getAssetScales().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(asset.trim()))
                .map(Map.Entry::getValue)
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("asset scale is not configured: " + asset));
        if (scale < 0L || scale > 18L) {
            throw new IllegalArgumentException("asset scale is invalid: " + asset);
        }
        try {
            BigDecimal value = new BigDecimal(amount.trim());
            if (value.signum() <= 0) {
                throw new IllegalArgumentException("amount must be positive");
            }
            return value.movePointRight(scale.intValue()).setScale(0, RoundingMode.UNNECESSARY).longValueExact();
        } catch (ArithmeticException | NumberFormatException ex) {
            throw new IllegalArgumentException("amount is not an exact asset unit amount", ex);
        }
    }

    public String subject(long userId) {
        if (userId <= 0L) {
            throw new IllegalArgumentException("userId must be positive");
        }
        return "user:" + userId;
    }

    String canonicalRequest(long timestampSeconds, String nonce, String method,
                            String requestTarget, byte[] body) {
        return timestampSeconds + "\n" + nonce + "\n" + method.toUpperCase(Locale.ROOT)
                + "\n" + requestTarget + "\n" + sha256(body == null ? new byte[0] : body);
    }

    String sign(String secret, String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(
                    mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception ex) {
            throw new IllegalStateException("wallet request signing failed", ex);
        }
    }

    private <T> T exchange(HttpMethod method, String path, Object payload,
                           Class<T> responseType, String idempotencyKey) {
        byte[] body = serialize(payload);
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        ensureConfigured(wallet);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = nonce();
        HttpHeaders headers = signedHeaders(wallet, timestamp, nonce, method, path, body);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    trimTrailingSlash(wallet.getBaseUrl()) + path,
                    method, new HttpEntity<>(body, headers), responseType);
            requireSuccess(response, "custody wallet");
            return response.getBody();
        } catch (RestClientResponseException ex) {
            throw new CustodyWalletRejectedException("custody wallet rejected request", ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("custody wallet request failed", ex);
        }
    }

    private <T> T exchange(HttpMethod method, String path, Object payload,
                           ParameterizedTypeReference<T> responseType, String idempotencyKey) {
        byte[] body = serialize(payload);
        GatewayProperties.CustodyWallet wallet = properties.getCustodyWallet();
        ensureConfigured(wallet);
        long timestamp = Instant.now().getEpochSecond();
        String nonce = nonce();
        HttpHeaders headers = signedHeaders(wallet, timestamp, nonce, method, path, body);
        if (idempotencyKey != null) {
            headers.set("Idempotency-Key", idempotencyKey);
        }
        try {
            ResponseEntity<T> response = restTemplate.exchange(
                    trimTrailingSlash(wallet.getBaseUrl()) + path,
                    method, new HttpEntity<>(body, headers), responseType);
            requireSuccess(response, "custody wallet");
            return response.getBody();
        } catch (RestClientResponseException ex) {
            throw new CustodyWalletRejectedException("custody wallet rejected request", ex.getStatusCode().value(), ex);
        } catch (RestClientException ex) {
            throw new IllegalStateException("custody wallet request failed", ex);
        }
    }

    private HttpHeaders signedHeaders(GatewayProperties.CustodyWallet wallet,
                                      long timestamp,
                                      String nonce,
                                      HttpMethod method,
                                      String path,
                                      byte[] body) {
        String canonical = canonicalRequest(timestamp, nonce, method.name(), path, body);
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(org.springframework.http.MediaType.APPLICATION_JSON);
        headers.set("X-Custody-Key", wallet.getApiKey());
        headers.set("X-Custody-Timestamp", Long.toString(timestamp));
        headers.set("X-Custody-Nonce", nonce);
        headers.set("X-Custody-Signature", sign(wallet.getApiSecret(), canonical));
        return headers;
    }

    private byte[] serialize(Object payload) {
        if (payload == null) {
            return new byte[0];
        }
        try {
            return objectMapper.writeValueAsBytes(payload);
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("wallet request cannot be serialized", ex);
        }
    }

    private void ensureConfigured(GatewayProperties.CustodyWallet wallet) {
        if (!wallet.isEnabled() || wallet.getBaseUrl() == null || wallet.getBaseUrl().isBlank()
                || wallet.getApiKey() == null || wallet.getApiKey().isBlank()
                || wallet.getApiSecret() == null || wallet.getApiSecret().isBlank()) {
            throw new IllegalStateException("custody wallet integration is not configured");
        }
    }

    private String nonce() {
        byte[] bytes = new byte[18];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private String query(Map<String, String> values) {
        StringBuilder result = new StringBuilder("?");
        values.forEach((key, value) -> {
            if (value != null && !value.isBlank()) {
                if (result.length() > 1) result.append('&');
                result.append(java.net.URLEncoder.encode(key, StandardCharsets.UTF_8))
                        .append('=')
                        .append(java.net.URLEncoder.encode(value, StandardCharsets.UTF_8));
            }
        });
        return result.length() == 1 ? "" : result.toString();
    }

    private String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is unavailable", ex);
        }
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim().toUpperCase(Locale.ROOT);
    }

    private String optional(String value) {
        return value == null ? "" : value.trim();
    }

    private String trimTrailingSlash(String value) {
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }

    private void requireSuccess(ResponseEntity<?> response, String service) {
        if (response == null || !response.getStatusCode().is2xxSuccessful()) {
            int status = response == null ? 0 : response.getStatusCode().value();
            throw new IllegalStateException(service + " returned HTTP " + status);
        }
    }

    public static final class CustodyWalletRejectedException extends IllegalStateException {
        private final int status;

        public CustodyWalletRejectedException(String message, int status, Throwable cause) {
            super(message, cause);
            this.status = status;
        }

        public int status() {
            return status;
        }
    }
}
