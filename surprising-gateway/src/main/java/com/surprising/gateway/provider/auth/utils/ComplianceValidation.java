package com.surprising.gateway.provider.auth.utils;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 合规写入的统一参数校验。
 */
public final class ComplianceValidation {

    private ComplianceValidation() {
    }

    public static String kycLevel(String value) {
        String normalized = defaultString(value, "NONE").trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "BASIC", "STANDARD", "ENHANCED", "INSTITUTIONAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid kycLevel");
        }
        return normalized;
    }

    public static String kycStatus(String value) {
        String normalized = defaultString(value, "UNVERIFIED").trim().toUpperCase(Locale.ROOT);
        if (!List.of("UNVERIFIED", "PENDING", "VERIFIED", "REJECTED", "EXPIRED").contains(normalized)) {
            throw new IllegalArgumentException("invalid kyc status");
        }
        return normalized;
    }

    public static String provider(String value) {
        String normalized = defaultString(value, "SELF").trim().toUpperCase(Locale.ROOT);
        if (!List.of("SELF", "THIRD_PARTY", "SUMSUB", "VERIFF").contains(normalized)) {
            throw new IllegalArgumentException("provider must be SELF, THIRD_PARTY, SUMSUB, or VERIFF");
        }
        return normalized;
    }

    public static String providerReference(String provider, String value) {
        String normalized = blankToNull(value);
        if (List.of("THIRD_PARTY", "SUMSUB", "VERIFF").contains(provider) && normalized == null) {
            throw new IllegalArgumentException("providerReference is required for third-party KYC");
        }
        if (normalized != null && normalized.length() > 240) {
            throw new IllegalArgumentException("providerReference is too long");
        }
        return normalized;
    }

    public static String amlStatus(String value) {
        String normalized = defaultString(value, "OPEN").trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "REVIEWING", "CLEARED", "ESCALATED", "RESTRICTED", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("invalid aml status");
        }
        return normalized;
    }

    public static String severity(String value) {
        String normalized = defaultString(value, "MEDIUM").trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid severity");
        }
        return normalized;
    }

    public static String country(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country must be ISO-3166 alpha-2");
        }
        return normalized;
    }

    public static String nullableUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    public static String nullableTag(String value) {
        return value == null || value.isBlank() ? null : tagCode(value);
    }

    public static String tagCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new IllegalArgumentException("invalid tagCode");
        }
        return normalized;
    }

    public static int riskScore(Integer value) {
        int score = value == null ? 0 : value;
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        return score;
    }

    public static String requiredText(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }

    public static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    public static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    public static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    public static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    public static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
    public static String applicantType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase();
        if (!normalized.equals("INDIVIDUAL") && !normalized.equals("BUSINESS")) {
            throw new IllegalArgumentException("applicantType must be INDIVIDUAL or BUSINESS");
        }
        return normalized;
    }

    public static String faceStatus(String value) {
        String normalized = value == null || value.isBlank() ? "NOT_REQUIRED" : value.trim().toUpperCase();
        if (!normalized.matches("NOT_REQUIRED|PENDING")) {
            throw new IllegalArgumentException("faceVerificationStatus may only be NOT_REQUIRED or PENDING");
        }
        return normalized;
    }
}
