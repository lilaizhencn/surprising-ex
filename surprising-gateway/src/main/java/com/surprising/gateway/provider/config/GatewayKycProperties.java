package com.surprising.gateway.provider.config;

import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import lombok.Getter;
import lombok.Setter;

/**
 * KYC verification, document storage, Sumsub, and Veriff configuration models.
 */
public final class GatewayKycProperties {

    private GatewayKycProperties() {
    }

    private static String stripTrailingSlashes(String value) {
        int end = value.length();
        while (end > 0 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static String stripSlashes(String value) {
        int start = 0;
        int end = value.length();
        while (start < end && value.charAt(start) == '/') {
            start++;
        }
        while (end > start && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(start, end);
    }

    @Getter
    public static class KycDocuments {
        @Setter
        private boolean enabled;
        private String type = "s3";
        private String endpoint = "";
        private String bucket = "";
        private String region = "us-east-1";
        private String accessKey = "";
        private String secretKey = "";
        private String rootPath = "/tmp/surprising-kyc-documents";
        private String prefix = "kyc";
        private long maxFileSizeBytes = 15L * 1024L * 1024L;

        public void setType(String type) {
            this.type = type == null || type.isBlank() ? "s3" : type.trim().toLowerCase(Locale.ROOT);
        }

        public void setEndpoint(String endpoint) {
            this.endpoint = endpoint == null ? "" : stripTrailingSlashes(endpoint.trim());
        }

        public void setBucket(String bucket) {
            this.bucket = bucket == null ? "" : bucket.trim();
        }

        public void setRegion(String region) {
            this.region = region == null || region.isBlank() ? "us-east-1" : region.trim();
        }

        public void setAccessKey(String accessKey) {
            this.accessKey = accessKey == null ? "" : accessKey.trim();
        }

        public void setSecretKey(String secretKey) {
            this.secretKey = secretKey == null ? "" : secretKey;
        }

        public void setRootPath(String rootPath) {
            this.rootPath = rootPath == null || rootPath.isBlank() ? "/tmp/surprising-kyc-documents" : rootPath;
        }

        public void setPrefix(String prefix) {
            this.prefix = prefix == null || prefix.isBlank() ? "kyc" : stripSlashes(prefix.trim());
        }

        public void setMaxFileSizeBytes(long maxFileSizeBytes) {
            this.maxFileSizeBytes = maxFileSizeBytes <= 0 ? 15L * 1024L * 1024L : maxFileSizeBytes;
        }
    }

    @Getter
    public static class Kyc {
        private String provider = "SUMSUB";
        private Duration validity = Duration.ofDays(365);
        @Setter
        private boolean simulationEnabled = true;
        private Sumsub sumsub = new Sumsub();
        private Veriff veriff = new Veriff();

        public void setValidity(Duration value) {
            if (value == null || value.isNegative() || value.isZero() || value.compareTo(Duration.ofDays(3650)) > 0) {
                throw new IllegalArgumentException("KYC validity must be positive and at most ten years");
            }
            validity = value;
        }

        public void setProvider(String provider) {
            this.provider = provider == null || provider.isBlank() ? "SUMSUB" : provider.trim().toUpperCase(Locale.ROOT);
        }

        public void setSumsub(Sumsub value) {
            sumsub = value == null ? new Sumsub() : value;
        }

        public void setVeriff(Veriff value) {
            veriff = value == null ? new Veriff() : value;
        }
    }

    @Getter
    public static class Sumsub {
        @Setter
        private String appToken = "";
        @Setter
        private String secretKey = "";
        @Setter
        private String webhookSecret = "";
        private Map<String, String> levelNames = new LinkedHashMap<>(
                Map.of("INDIVIDUAL_BASIC", "basic-kyc-level"));
        private String baseUrl = "https://api.sumsub.com";

        public void setLevelNames(Map<String, String> values) {
            levelNames = new LinkedHashMap<>();
            if (values != null) {
                values.forEach((key, value) -> {
                    String name = key.trim().toUpperCase(Locale.ROOT).replace('-', '_');
                    if (!name.matches("(INDIVIDUAL|BUSINESS)_(BASIC|STANDARD|ENHANCED)")) {
                        throw new IllegalArgumentException("unsupported Sumsub verification option");
                    }
                    String flow = value == null ? "" : value.trim();
                    if (!flow.isEmpty() && levelNames.containsValue(flow)) {
                        throw new IllegalArgumentException("each KYC option requires a distinct Sumsub flow");
                    }
                    levelNames.put(name, flow);
                });
            }
        }

        public String levelName(String applicantType, String kycLevel) {
            String value = levelNames.get(applicantType + "_" + kycLevel);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("the requested KYC verification flow is not configured");
            }
            return value;
        }

        public void setBaseUrl(String value) {
            baseUrl = value == null || value.isBlank() ? "https://api.sumsub.com" : stripTrailingSlashes(value.trim());
        }
    }

    @Getter
    public static class Veriff {
        private String kycLevel = "BASIC";
        @Setter
        private String apiKey = "";
        @Setter
        private String sharedSecret = "";
        private String baseUrl = "https://stationapi.veriff.com";
        @Setter
        private String callbackUrl = "";

        public void setKycLevel(String value) {
            String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
            if (!List.of("BASIC", "STANDARD", "ENHANCED").contains(normalized)) {
                throw new IllegalArgumentException("unsupported Veriff verification level");
            }
            kycLevel = normalized;
        }

        public void setBaseUrl(String value) {
            baseUrl = value == null || value.isBlank()
                    ? "https://stationapi.veriff.com" : stripTrailingSlashes(value.trim());
        }
    }
}
