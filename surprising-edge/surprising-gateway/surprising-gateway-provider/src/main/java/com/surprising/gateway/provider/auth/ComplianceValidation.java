package com.surprising.gateway.provider.auth;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;

/**
 * 合规写入的统一参数校验。
 */
final class ComplianceValidation {

    private ComplianceValidation() {
    }

    static String kycLevel(String value) {
        String normalized = defaultString(value, "NONE").trim().toUpperCase(Locale.ROOT);
        if (!List.of("NONE", "BASIC", "STANDARD", "ENHANCED", "INSTITUTIONAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid kycLevel");
        }
        return normalized;
    }

    static String kycStatus(String value) {
        String normalized = defaultString(value, "UNVERIFIED").trim().toUpperCase(Locale.ROOT);
        if (!List.of("UNVERIFIED", "PENDING", "VERIFIED", "REJECTED", "EXPIRED").contains(normalized)) {
            throw new IllegalArgumentException("invalid kyc status");
        }
        return normalized;
    }

    static String provider(String value) {
        String normalized = defaultString(value, "SELF").trim().toUpperCase(Locale.ROOT);
        if (!List.of("SELF", "THIRD_PARTY").contains(normalized)) {
            throw new IllegalArgumentException("provider must be SELF or THIRD_PARTY");
        }
        return normalized;
    }

    static String providerReference(String provider, String value) {
        String normalized = blankToNull(value);
        if ("THIRD_PARTY".equals(provider) && normalized == null) {
            throw new IllegalArgumentException("providerReference is required for THIRD_PARTY");
        }
        if (normalized != null && normalized.length() > 240) {
            throw new IllegalArgumentException("providerReference is too long");
        }
        return normalized;
    }

    static String amlStatus(String value) {
        String normalized = defaultString(value, "OPEN").trim().toUpperCase(Locale.ROOT);
        if (!List.of("OPEN", "REVIEWING", "CLEARED", "ESCALATED", "RESTRICTED", "CLOSED").contains(normalized)) {
            throw new IllegalArgumentException("invalid aml status");
        }
        return normalized;
    }

    static String severity(String value) {
        String normalized = defaultString(value, "MEDIUM").trim().toUpperCase(Locale.ROOT);
        if (!List.of("LOW", "MEDIUM", "HIGH", "CRITICAL").contains(normalized)) {
            throw new IllegalArgumentException("invalid severity");
        }
        return normalized;
    }

    static String country(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z]{2}")) {
            throw new IllegalArgumentException("country must be ISO-3166 alpha-2");
        }
        return normalized;
    }

    static String nullableUpper(String value) {
        return value == null || value.isBlank() ? null : value.trim().toUpperCase(Locale.ROOT);
    }

    static String nullableTag(String value) {
        return value == null || value.isBlank() ? null : tagCode(value);
    }

    static String tagCode(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,64}")) {
            throw new IllegalArgumentException("invalid tagCode");
        }
        return normalized;
    }

    static int riskScore(Integer value) {
        int score = value == null ? 0 : value;
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("riskScore must be between 0 and 100");
        }
        return score;
    }

    static String requiredText(String value, String field) {
        String normalized = blankToNull(value);
        if (normalized == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized.length() > 2000 ? normalized.substring(0, 2000) : normalized;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    static String defaultString(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    static Timestamp timestamp(Instant value) {
        return value == null ? null : Timestamp.from(value);
    }

    static Long nullableLong(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    static Instant nullableInstant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }
}
