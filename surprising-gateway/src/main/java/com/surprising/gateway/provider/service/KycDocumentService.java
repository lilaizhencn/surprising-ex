package com.surprising.gateway.provider.service;

import tools.jackson.databind.JsonNode;
import java.util.Locale;
import java.util.Set;
import com.surprising.gateway.provider.storage.KycDocumentStorageService;
import com.surprising.gateway.provider.exception.KycDocumentNotFoundException;
import com.surprising.gateway.provider.exception.KycDocumentStorageException;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.repository.KycDocumentRepository;
import com.surprising.gateway.provider.config.GatewayProperties;
import static com.surprising.gateway.provider.utils.KycDocumentFormats.normalizeDocumentType;

import com.surprising.gateway.provider.model.KycDocumentUpload;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class KycDocumentService {

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

    public KycDocument upload(long userId, KycDocumentUpload upload) {
        if (userId <= 0) throw new IllegalArgumentException("user id must be positive");
        byte[] content = upload.content();
        String type = upload.documentType();
        String contentType = upload.contentType();
        String extension = upload.extension();
        String objectKey = KycDocumentStorageService.objectKey(
                properties.getKycDocuments().getPrefix(), userId, extension);
        String sha256 = sha256(content);
        String filename = upload.filename();
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

    public void deleteDraft(long userId, long documentId) {
        requireForUser(userId, documentId);
        if (repository.deleteDraft(userId, documentId) != 1) {
            throw new IllegalStateException("only unsubmitted documents can be deleted");
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
        return requireSubmissionDocuments(userId, documentIds, documentType, null, null, null);
    }

    public List<KycDocument> requireSubmissionDocuments(long userId,
                                                        List<Long> documentIds,
                                                        String documentType,
                                                        String applicantType,
                                                        String kycLevel,
                                                        String faceVerificationStatus) {
        if (documentIds == null || documentIds.isEmpty()) {
            throw new IllegalArgumentException("upload KYC documents before submitting verification");
        }
        List<KycDocument> documents = repository.findOwnedForSubmission(userId, documentIds);
        if (documents.size() != documentIds.size()) {
            throw new IllegalArgumentException("KYC document does not belong to the current user or is unavailable");
        }
        String requestedType = normalizeDocumentType(documentType);
        if ("ID_CARD".equals(requestedType)) {
            for (String required : List.of("ID_CARD_FRONT", "ID_CARD_BACK", "ID_CARD_SELFIE")) {
                if (documents.stream().noneMatch(document -> required.equals(document.documentType()))) {
                    throw new IllegalArgumentException("KYC document does not match: missing " + required);
                }
            }
        } else if (Set.of("DRIVING_LICENSE", "RESIDENCE_PERMIT").contains(requestedType)) {
            for (String side : List.of("_FRONT", "_BACK")) {
                if (documents.stream().noneMatch(document -> (requestedType + side).equals(document.documentType()))) {
                    throw new IllegalArgumentException("KYC document does not match: missing " + requestedType + side);
                }
            }
        } else if (documents.stream().noneMatch(document -> requestedType.equals(document.documentType()))) {
            throw new IllegalArgumentException("KYC document type does not match submitted verification");
        }
        requireProfileDocuments(documents, applicantType, kycLevel, faceVerificationStatus);
        return documents;
    }

    private void requireProfileDocuments(List<KycDocument> documents,
                                         String applicantType,
                                         String kycLevel,
                                         String faceVerificationStatus) {
        if (applicantType == null && kycLevel == null && faceVerificationStatus == null) {
            return;
        }
        String normalizedApplicantType = normalizeProfileValue(applicantType, "applicantType");
        if (!Set.of("INDIVIDUAL", "BUSINESS").contains(normalizedApplicantType)) {
            throw new IllegalArgumentException("applicantType must be INDIVIDUAL or BUSINESS");
        }
        String normalizedKycLevel = normalizeProfileValue(kycLevel, "kycLevel");
        if (!Set.of("BASIC", "STANDARD", "ENHANCED").contains(normalizedKycLevel)) {
            throw new IllegalArgumentException("kycLevel must be BASIC, STANDARD or ENHANCED");
        }
        String normalizedFaceStatus = faceVerificationStatus == null || faceVerificationStatus.isBlank()
                ? "NOT_REQUIRED" : normalizeProfileValue(faceVerificationStatus, "faceVerificationStatus");
        if (!Set.of("NOT_REQUIRED", "PENDING").contains(normalizedFaceStatus)) {
            throw new IllegalArgumentException("faceVerificationStatus may only be NOT_REQUIRED or PENDING");
        }

        boolean identity = documents.stream().anyMatch(document ->
                Set.of("ID_CARD_FRONT", "PASSPORT", "DRIVING_LICENSE_FRONT", "RESIDENCE_PERMIT_FRONT").contains(document.documentType()));
        if (!identity) {
            throw new IllegalArgumentException("an identity document is required for KYC");
        }
        if ("BUSINESS".equals(normalizedApplicantType)
                && documents.stream().noneMatch(document -> "BUSINESS_LICENSE".equals(document.documentType()))) {
            throw new IllegalArgumentException("a business license is required for business KYC");
        }
        if (Set.of("STANDARD", "ENHANCED").contains(normalizedKycLevel)
                && documents.stream().noneMatch(document -> "ADDRESS_PROOF".equals(document.documentType()))) {
            throw new IllegalArgumentException("an address proof is required for this KYC level");
        }
        if ("ENHANCED".equals(normalizedKycLevel) && !"PENDING".equals(normalizedFaceStatus)) {
            throw new IllegalArgumentException("enhanced KYC requires face verification");
        }
        if ("PENDING".equals(normalizedFaceStatus)
                && documents.stream().noneMatch(document -> Set.of("FACE_IMAGE", "ID_CARD_SELFIE").contains(document.documentType()))) {
            throw new IllegalArgumentException("a face image is required when face verification is enabled");
        }
    }

    private String normalizeProfileValue(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim().toUpperCase(Locale.ROOT);
    }

    public String references(List<KycDocument> documents) {
        return references(documents, null, null);
    }

    public String references(List<KycDocument> documents, java.time.LocalDate expiresOn, java.time.LocalDate issuedOn) {
        return references(documents, expiresOn, issuedOn, null);
    }

    public String references(List<KycDocument> documents, java.time.LocalDate expiresOn,
                             java.time.LocalDate addressIssuedOn, java.time.LocalDate documentIssuedOn) {
        try {
            return validateReferences(objectMapper.writeValueAsString(documents.stream()
                    .map(document -> Map.of(
                            "type", document.documentType(),
                            "reference", "document:" + document.documentId(),
                            "sha256", document.sha256(),
                            "documentExpiresOn", expiresOn == null ? "" : expiresOn.toString(),
                            "documentIssuedOn", document.documentType().startsWith("ID_CARD_") && documentIssuedOn != null ? documentIssuedOn.toString() : "",
                            "addressIssuedOn", "ADDRESS_PROOF".equals(document.documentType()) && addressIssuedOn != null ? addressIssuedOn.toString() : ""))
                    .toList()));
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

    private String sha256(byte[] content) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (Exception ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }
    private String validateReferences(String value) {
        String normalized = value == null || value.isBlank() ? "[]" : value.trim();
        try {
            JsonNode parsed = objectMapper.readTree(normalized);
            if (parsed == null || !parsed.isArray()) {
                throw new IllegalArgumentException("submittedDocuments must be a JSON array");
            }
            if (parsed.size() > 10) {
                throw new IllegalArgumentException("submittedDocuments may contain at most 10 items");
            }
            Set<String> allowedTypes = Set.of(
                    "DRIVING_LICENSE", "DRIVING_LICENSE_FRONT", "DRIVING_LICENSE_BACK", "RESIDENCE_PERMIT", "RESIDENCE_PERMIT_FRONT", "RESIDENCE_PERMIT_BACK", "ID_CARD", "ID_CARD_FRONT", "ID_CARD_BACK", "ID_CARD_SELFIE", "PASSPORT", "ADDRESS_PROOF", "BUSINESS_LICENSE", "FACE_IMAGE");
            for (JsonNode document : parsed) {
                if (!document.isObject()) {
                    throw new IllegalArgumentException("each submitted document must be an object");
                }
                JsonNode type = document.get("type");
                JsonNode reference = document.get("reference");
                String normalizedType = type == null ? "" : type.asString("").trim().toUpperCase(Locale.ROOT);
                String normalizedReference = reference == null ? "" : reference.asString("").trim();
                if (!allowedTypes.contains(normalizedType) || normalizedReference.isBlank()
                        || normalizedReference.length() > 240) {
                    throw new IllegalArgumentException("submitted document type or reference is invalid");
                }
            }
        } catch (JacksonException ex) {
            throw new IllegalArgumentException("submittedDocuments must be valid JSON", ex);
        }
        if (normalized.length() > 8000) {
            throw new IllegalArgumentException("submittedDocuments must be a JSON array");
        }
        return normalized;
    }
}
