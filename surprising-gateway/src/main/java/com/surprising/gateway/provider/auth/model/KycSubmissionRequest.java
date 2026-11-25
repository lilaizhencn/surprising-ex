package com.surprising.gateway.provider.auth.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.util.List;

public record KycSubmissionRequest(
        @NotBlank @Size(max = 20) String applicantType,
        @NotBlank @Size(max = 20) String kycLevel,
        @NotBlank @Size(min = 2, max = 2) String country,
        @NotBlank @Size(max = 40) String documentType,
        @Size(max = 40) String provider,
        @Size(max = 240) String providerReference,
        @Size(max = 8000) String submittedDocuments,
        @Size(max = 40) String faceVerificationStatus,
        @Size(max = 10) List<Long> documentIds,
        LocalDate documentExpiresOn,
        LocalDate addressIssuedOn,
        LocalDate documentIssuedOn) {
    public KycSubmissionRequest(String applicantType, String kycLevel, String country, String documentType,
            String provider, String providerReference, String submittedDocuments, String faceVerificationStatus,
            List<Long> documentIds, LocalDate documentExpiresOn, LocalDate addressIssuedOn) {
        this(applicantType, kycLevel, country, documentType, provider, providerReference, submittedDocuments,
                faceVerificationStatus, documentIds, documentExpiresOn, addressIssuedOn, null);
    }
    public KycSubmissionRequest(String applicantType, String kycLevel, String country, String documentType,
            String provider, String providerReference, String submittedDocuments, String faceVerificationStatus,
            List<Long> documentIds) {
        this(applicantType,kycLevel,country,documentType,provider,providerReference,submittedDocuments,
                faceVerificationStatus,documentIds,null,null,null);
    }
}
