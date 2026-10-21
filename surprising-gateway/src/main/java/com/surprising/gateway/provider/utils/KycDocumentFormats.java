package com.surprising.gateway.provider.utils;

import java.util.Locale;
import java.util.Set;

/** KYC文档输入的纯格式处理，不访问存储或业务状态。 */
public final class KycDocumentFormats {
    private KycDocumentFormats() {}
    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "DRIVING_LICENSE", "DRIVING_LICENSE_FRONT", "DRIVING_LICENSE_BACK", "RESIDENCE_PERMIT", "RESIDENCE_PERMIT_FRONT", "RESIDENCE_PERMIT_BACK", "ID_CARD", "ID_CARD_FRONT", "ID_CARD_BACK", "ID_CARD_SELFIE", "PASSPORT", "ADDRESS_PROOF", "BUSINESS_LICENSE", "FACE_IMAGE");
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    public static String normalizeDocumentType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!DOCUMENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("invalid KYC document type");
        }
        return normalized;
    }

    public static String normalizeContentType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    public static String detectedContentType(byte[] content) {
        if (content.length >= 5 && content[0] == '%' && content[1] == 'P' && content[2] == 'D'
                && content[3] == 'F' && content[4] == '-') {
            return "application/pdf";
        }
        if (content.length >= 3 && (content[0] & 0xff) == 0xff && (content[1] & 0xff) == 0xd8
                && (content[2] & 0xff) == 0xff) {
            return "image/jpeg";
        }
        if (content.length >= 8 && (content[0] & 0xff) == 0x89 && content[1] == 'P' && content[2] == 'N'
                && content[3] == 'G' && (content[4] & 0xff) == 0x0d && (content[5] & 0xff) == 0x0a
                && (content[6] & 0xff) == 0x1a && content[7] == '\n') {
            return "image/png";
        }
        throw new IllegalArgumentException("KYC document format is not supported");
    }

    public static String extension(String contentType) {
        if (!CONTENT_TYPES.contains(contentType)) {
            throw new IllegalArgumentException("KYC document content type is not supported");
        }
        return switch (contentType) {
            case "application/pdf" -> ".pdf";
            case "image/jpeg" -> ".jpg";
            case "image/png" -> ".png";
            default -> throw new IllegalArgumentException("KYC document content type is not supported");
        };
    }

    public static String safeFilename(String original, String extension) {
        String value = original == null || original.isBlank() ? "document" + extension : original;
        value = value.replace('\\', '/');
        int slash = value.lastIndexOf('/');
        value = slash >= 0 ? value.substring(slash + 1) : value;
        value = value.replaceAll("[^A-Za-z0-9._-]", "_");
        if (value.isBlank() || value.equals(".") || value.equals("..")) {
            value = "document" + extension;
        }
        return value.length() > 160 ? value.substring(0, 160) : value;
    }

}
