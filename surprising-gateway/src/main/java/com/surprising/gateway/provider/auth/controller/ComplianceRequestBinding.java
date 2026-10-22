package com.surprising.gateway.provider.auth.controller;

import com.surprising.gateway.provider.auth.model.ComplianceModels.*;
import com.surprising.gateway.provider.auth.utils.ComplianceValidation;

/** 合规 HTTP 输入格式绑定；状态流转、资料归属、第三方事实及事务由 Service 负责。 */
public final class ComplianceRequestBinding {
    private ComplianceRequestBinding() {
    }

    public static KycUpdateRequest review(KycUpdateRequest request) {
        String provider = ComplianceValidation.provider(request.provider());
        return new KycUpdateRequest(ComplianceValidation.kycLevel(request.kycLevel()),
                ComplianceValidation.kycStatus(request.status()), ComplianceValidation.country(request.country()),
                ComplianceValidation.blankToNull(request.documentType()), provider,
                ComplianceValidation.providerReference(provider, request.providerReference()),
                ComplianceValidation.blankToNull(request.rejectionReason()), request.expiresAt());
    }

    public static KycSubmissionRequest submission(KycSubmissionRequest request) {
        return new KycSubmissionRequest(ComplianceValidation.applicantType(request.applicantType()),
                ComplianceValidation.kycLevel(request.kycLevel()), ComplianceValidation.country(request.country()),
                ComplianceValidation.blankToNull(request.documentType()), request.provider(), request.providerReference(),
                request.submittedDocuments(), ComplianceValidation.faceStatus(request.faceVerificationStatus()),
                request.documentIds(), request.documentExpiresOn(), request.addressIssuedOn(), request.documentIssuedOn());
    }

    public static RiskTagCreateRequest riskTag(RiskTagCreateRequest request) {
        return new RiskTagCreateRequest(ComplianceValidation.tagCode(request.tagCode()),
                ComplianceValidation.severity(request.severity()), ComplianceValidation.blankToNull(request.source()),
                ComplianceValidation.requiredText(request.reason(), "reason"));
    }

    public static AmlCaseCreateRequest amlCase(AmlCaseCreateRequest request) {
        return new AmlCaseCreateRequest(ComplianceValidation.amlStatus(request.status()),
                ComplianceValidation.riskScore(request.riskScore()), ComplianceValidation.blankToNull(request.source()),
                ComplianceValidation.requiredText(request.summary(), "summary"), request.assignedAdminUserId());
    }

    public static AmlCaseStatusUpdateRequest amlStatus(AmlCaseStatusUpdateRequest request) {
        return new AmlCaseStatusUpdateRequest(ComplianceValidation.amlStatus(request.status()),
                request.riskScore() == null ? null : ComplianceValidation.riskScore(request.riskScore()));
    }
}
