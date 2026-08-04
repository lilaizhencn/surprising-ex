package com.surprising.gateway.provider.service;

import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.KycDocumentRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class KycDocumentService {

    private static final Set<String> DOCUMENT_TYPES = Set.of(
            "ID_CARD", "PASSPORT", "ADDRESS_PROOF", "BUSINESS_LICENSE", "FACE_IMAGE");
    private static final Set<String> CONTENT_TYPES = Set.of("application/pdf", "image/jpeg", "image/png");

    private final KycDocumentRepository repository;
    private final KycDocumentStorageService storage;
    private final GatewayProperties properties;
    private final ObjectMapper objectMapper;

    public KycDocumentService(KycDocumentRepository repository,
                              KycDocumentStorageService storage,
                              GatewayProperties properties,
                              ObjectMapper objectMapper) {
        this.repository = repository;
        this.storage = storage;
        this.properties = properties;
        this.objectMapper = objectMapper == null ? new ObjectMapper() : objectMapper;
    }

    public KycDocument upload(long userId, String documentType, MultipartFile file) {
        if (userId <= 0) {
            throw new IllegalArgumentException("user id must be positive");
        }
        String type = normalizeDocumentType(documentType);
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("KYC document file is required");
        }
        byte[] content;
        try {
            content = file.getBytes();
        } catch (java.io.IOException ex) {
            throw new IllegalArgumentException("KYC document file cannot be read", ex);
        }
        if (content.length == 0 || content.length > properties.getKycDocuments().getMaxFileSizeBytes()) {
            throw new IllegalArgumentException("KYC document file size is invalid");
        }
        String contentType = detectedContentType(content);
        String declaredContentType = normalizeContentType(file.getContentType());
        if (declaredContentType != null && !"application/octet-stream".equals(declaredContentType)
                && !contentType.equals(declaredContentType)) {
            throw new IllegalArgumentException("KYC document content type does not match file content");
        }
        String extension = extension(contentType);
        String objectKey = KycDocumentStorageService.objectKey(
                properties.getKycDocuments().getPrefix(), userId, extension);
        String sha256 = sha256(content);
        String filename = safeFilename(file.getOriginalFilename(), extension);
        storage.store(objectKey, content, contentType);
        try {
            return repository.insert(userId, type, filename, contentType, content.length, sha256, objectKey, java.time.Instant.now());
        } catch (RuntimeException ex) {
            try {
                storage.delete(objectKey);
            } catch (RuntimeException cleanupFailure) {
                ex.addSuppressed(cleanupFailure);
            }
            throw ex;
        }
    }

    public List<KycDocument> findForUser(long userId) {
        return repository.findForUser(userId);
    }

    public KycDocument requireForUser(long userId, long documentId) {
        KycDocument document = repository.findForUser(userId, documentId);
        if (document == null) {
            throw new KycDocumentNotFoundException("KYC document not found");
        }
        return document;
    }

    public KycDocument requireForAdmin(long userId, long documentId) {
        KycDocument document = repository.findForAdmin(userId, documentId);
        if (document == null) {
            throw new KycDocumentNotFoundException("KYC document not found");
        }
        return document;
    }

    public byte[] read(KycDocument document) {
        byte[] content = storage.read(repository.objectKey(document.userId(), document.documentId()));
        if (!sha256(content).equals(document.sha256())) {
            throw new KycDocumentStorageException("KYC document integrity check failed");
        }
        return content;
    }

    public List<KycDocument> requireSubmissionDocuments(long userId, List<Long> documentIds, String documentType) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new IllegalArgumentException("upload KYC documents before submitting verification");
        }
        List<KycDocument> documents = repository.findOwnedForSubmission(userId, documentIds);
        if (documents.size() != documentIds.size()) {
            throw new IllegalArgumentException("KYC document does not belong to the current user or is unavailable");
        }
        String requestedType = normalizeDocumentType(documentType);
        if (documents.stream().noneMatch(document -> requestedType.equals(document.documentType()))) {
            throw new IllegalArgumentException("KYC document type does not match submitted verification");
        }
        return documents;
    }

    public String references(List<KycDocument> documents) {
        try {
            return objectMapper.writeValueAsString(documents.stream()
                    .map(document -> Map.of(
                            "type", document.documentType(),
                            "reference", "document:" + document.documentId(),
                            "sha256", document.sha256()))
                    .toList());
        } catch (JacksonException ex) {
            throw new IllegalStateException("KYC document references cannot be serialized", ex);
        }
    }

    public void markSubmitted(long userId, List<Long> documentIds) {
        int updated = repository.markSubmitted(userId, documentIds);
        if (updated != documentIds.size()) {
            throw new IllegalStateException("KYC document submission state could not be updated");
        }
    }

    private String normalizeDocumentType(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
        if (!DOCUMENT_TYPES.contains(normalized)) {
            throw new IllegalArgumentException("invalid KYC document type");
        }
        return normalized;
    }

    private String normalizeContentType(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toLowerCase(Locale.ROOT);
        return "image/jpg".equals(normalized) ? "image/jpeg" : normalized;
    }

    private String detectedContentType(byte[] content) {
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

    private String extension(String contentType) {
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

    private String safeFilename(String original, String extension) {
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

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
}
