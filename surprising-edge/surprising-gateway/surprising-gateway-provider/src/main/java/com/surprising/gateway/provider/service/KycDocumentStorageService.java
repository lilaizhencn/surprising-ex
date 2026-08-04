package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.config.GatewayProperties;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Service;

@Service
public class KycDocumentStorageService {

    private static final DateTimeFormatter AWS_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'")
            .withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AWS_DAY = DateTimeFormatter.ofPattern("yyyyMMdd")
            .withZone(ZoneOffset.UTC);

    private final GatewayProperties properties;
    private final HttpClient httpClient;

    public KycDocumentStorageService(GatewayProperties properties) {
        this.properties = properties;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getHttpClient().getConnectTimeout())
                .build();
    }

    public void ensureEnabled() {
        if (!properties.getKycDocuments().isEnabled()) {
            throw new KycDocumentStorageUnavailableException("KYC document storage is not enabled");
        }
    }

    public void store(String objectKey, byte[] content, String contentType) {
        ensureEnabled();
        GatewayProperties.KycDocuments config = properties.getKycDocuments();
        if ("filesystem".equals(config.getType())) {
            storeFilesystem(config, objectKey, content);
            return;
        }
        if (!"s3".equals(config.getType())) {
            throw new KycDocumentStorageUnavailableException("unsupported KYC document storage type");
        }
        request("PUT", objectKey, content, contentType);
    }

    public byte[] read(String objectKey) {
        ensureEnabled();
        GatewayProperties.KycDocuments config = properties.getKycDocuments();
        if ("filesystem".equals(config.getType())) {
            try {
                return Files.readAllBytes(filesystemPath(config, objectKey));
            } catch (IOException ex) {
                throw new KycDocumentStorageException("KYC document read failed", ex);
            }
        }
        if (!"s3".equals(config.getType())) {
            throw new KycDocumentStorageUnavailableException("unsupported KYC document storage type");
        }
        return request("GET", objectKey, new byte[0], null);
    }

    public void delete(String objectKey) {
        if (!properties.getKycDocuments().isEnabled()) {
            return;
        }
        GatewayProperties.KycDocuments config = properties.getKycDocuments();
        if ("filesystem".equals(config.getType())) {
            try {
                Files.deleteIfExists(filesystemPath(config, objectKey));
            } catch (IOException ex) {
                throw new KycDocumentStorageException("KYC document cleanup failed", ex);
            }
            return;
        }
        if ("s3".equals(config.getType())) {
            request("DELETE", objectKey, new byte[0], null);
            return;
        }
        throw new KycDocumentStorageUnavailableException("unsupported KYC document storage type");
    }

    private void storeFilesystem(GatewayProperties.KycDocuments config, String objectKey, byte[] content) {
        try {
            Path path = filesystemPath(config, objectKey);
            Files.createDirectories(path.getParent());
            Files.write(path, content, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
        } catch (IOException ex) {
            throw new KycDocumentStorageException("KYC document write failed", ex);
        }
    }

    private Path filesystemPath(GatewayProperties.KycDocuments config, String objectKey) {
        if (objectKey == null || objectKey.contains("..")
                || !objectKey.matches("[A-Za-z0-9._/-]{1,240}")) {
            throw new IllegalArgumentException("invalid KYC document object key");
        }
        Path root = Path.of(config.getRootPath()).toAbsolutePath().normalize();
        Path path = root.resolve(objectKey).normalize();
        if (!path.startsWith(root)) {
            throw new IllegalArgumentException("invalid KYC document object key");
        }
        return path;
    }

    private byte[] request(String method, String objectKey, byte[] body, String contentType) {
        GatewayProperties.KycDocuments config = properties.getKycDocuments();
        validateS3(config, objectKey);
        URI uri = objectUri(config, objectKey);
        String payloadHash = sha256(body);
        Instant now = Instant.now();
        String timestamp = AWS_DATE.format(now);
        String day = AWS_DAY.format(now);
        String host = uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : "");
        String canonicalHeaders = "host:" + host + "\n"
                + "x-amz-content-sha256:" + payloadHash + "\n"
                + "x-amz-date:" + timestamp + "\n";
        String signedHeaders = "host;x-amz-content-sha256;x-amz-date";
        String canonicalRequest = method + "\n" + uri.getRawPath() + "\n\n"
                + canonicalHeaders + "\n" + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + config.getRegion() + "/s3/aws4_request";
        String credential = config.getAccessKey() + "/" + scope;
        String signature = hmacHex(signingKey(config.getSecretKey(), day, config.getRegion(), "s3"),
                "AWS4-HMAC-SHA256\n" + timestamp + "\n" + scope + "\n" + sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        HttpRequest.Builder builder = HttpRequest.newBuilder(uri)
                .timeout(properties.getHttpClient().getReadTimeout())
                .header("x-amz-content-sha256", payloadHash)
                .header("x-amz-date", timestamp)
                .header("Authorization", "AWS4-HMAC-SHA256 Credential=" + credential
                        + ", SignedHeaders=" + signedHeaders + ", Signature=" + signature);
        if (contentType != null && !contentType.isBlank()) {
            builder.header("Content-Type", contentType);
        }
        try {
            HttpResponse<byte[]> response = httpClient.send(
                    builder.method(method, HttpRequest.BodyPublishers.ofByteArray(body)).build(),
                    HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                throw new KycDocumentStorageException("KYC document storage request failed: " + response.statusCode());
            }
            return response.body();
        } catch (IOException ex) {
            throw new KycDocumentStorageException("KYC document storage request failed", ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new KycDocumentStorageException("KYC document storage request interrupted", ex);
        }
    }

    private void validateS3(GatewayProperties.KycDocuments config, String objectKey) {
        if (config.getEndpoint().isBlank() || config.getBucket().isBlank()
                || config.getAccessKey().isBlank() || config.getSecretKey().isBlank()) {
            throw new KycDocumentStorageUnavailableException("KYC S3 storage configuration is incomplete");
        }
        URI endpoint = URI.create(config.getEndpoint());
        if (!"http".equalsIgnoreCase(endpoint.getScheme()) && !"https".equalsIgnoreCase(endpoint.getScheme())) {
            throw new KycDocumentStorageUnavailableException("KYC S3 endpoint must use HTTP or HTTPS");
        }
        if (endpoint.getHost() == null || endpoint.getRawQuery() != null || endpoint.getRawFragment() != null
                || !config.getBucket().matches("[A-Za-z0-9.-]{3,63}")
                || objectKey == null || objectKey.contains("..")
                || !objectKey.matches("[A-Za-z0-9._/-]{1,240}")) {
            throw new KycDocumentStorageUnavailableException("KYC S3 storage configuration is invalid");
        }
    }

    private URI objectUri(GatewayProperties.KycDocuments config, String objectKey) {
        String basePath = URI.create(config.getEndpoint()).getRawPath();
        String path = (basePath == null ? "" : basePath.replaceAll("/$", ""))
                + "/" + config.getBucket() + "/" + objectKey;
        URI endpoint = URI.create(config.getEndpoint());
        return URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + path);
    }

    private byte[] signingKey(String secret, String day, String region, String service) {
        byte[] dateKey = hmac("AWS4" + secret, day);
        byte[] regionKey = hmac(dateKey, region);
        byte[] serviceKey = hmac(regionKey, service);
        return hmac(serviceKey, "aws4_request");
    }

    private byte[] hmac(String key, String value) {
        return hmac(key.getBytes(StandardCharsets.UTF_8), value);
    }

    private byte[] hmac(byte[] key, String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(value.getBytes(StandardCharsets.UTF_8));
        } catch (Exception ex) {
            throw new KycDocumentStorageException("KYC S3 signature failed", ex);
        }
    }

    private String hmacHex(byte[] key, String value) {
        return HexFormat.of().formatHex(hmac(key, value));
    }

    private String sha256(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (Exception ex) {
            throw new KycDocumentStorageException("SHA-256 is not available", ex);
        }
    }

    public static String objectKey(String prefix, long userId, String extension) {
        String normalizedPrefix = prefix == null || prefix.isBlank() ? "kyc" : prefix;
        if (!normalizedPrefix.matches("[A-Za-z0-9/_-]{1,80}") || userId <= 0
                || !extension.matches("\\.[a-z]{2,4}")) {
            throw new IllegalArgumentException("invalid KYC document object key components");
        }
        return normalizedPrefix + "/" + userId + "/" + UUID.randomUUID() + extension.toLowerCase(Locale.ROOT);
    }
}
