package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTagCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.time.Instant;
import java.util.List;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Service;

/**
 * 在服务层聚合合规用户投影以及 KYC、风险标签、AML case 单表仓储。
 */
@Service
public class ComplianceService {

    private final ComplianceUserProjectionRepository userProjectionRepository;
    private final ComplianceKycRepository kycRepository;
    private final ComplianceRiskTagRepository riskTagRepository;
    private final ComplianceAmlCaseRepository amlCaseRepository;
    private final AuthService authService;
    private final AdminApprovalService approvalService;
    private final KycDocumentService kycDocumentService;
    private final AdminAuditRepository adminAuditRepository;

    public ComplianceService(ComplianceUserProjectionRepository userProjectionRepository,
                             ComplianceKycRepository kycRepository,
                             ComplianceRiskTagRepository riskTagRepository,
                             ComplianceAmlCaseRepository amlCaseRepository,
                             AuthService authService,
                             AdminApprovalService approvalService,
                             KycDocumentService kycDocumentService,
                             AdminAuditRepository adminAuditRepository) {
        this.userProjectionRepository = userProjectionRepository;
        this.kycRepository = kycRepository;
        this.riskTagRepository = riskTagRepository;
        this.amlCaseRepository = amlCaseRepository;
        this.authService = authService;
        this.approvalService = approvalService;
        this.kycDocumentService = kycDocumentService;
        this.adminAuditRepository = adminAuditRepository;
    }

    public AdminCursorPage.CursorPage<ComplianceUserSummary> adminUsersPage(
            String authorization,
            Long userId,
            String kycStatus,
            String tagCode,
            int limit,
            String cursor,
            String sort) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return usersPage(userId, kycStatus, tagCode, limit, cursor, sort);
    }

    public String approvalHeaderName() {
        return approvalService.approvalHeaderName();
    }

    public AdminComplianceUserDetail adminUser(String authorization, long userId) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        AuthenticatedUser user = authService.adminUser(authorization, userId);
        return new AdminComplianceUserDetail(
                user,
                kyc(userId),
                kycDocumentService.findForUser(userId),
                riskTags(userId, null, 200),
                amlCases(userId, null, 200));
    }

    public AdminCursorPage.CursorPage<RiskTag> adminRiskTagsPage(
            String authorization,
            Long userId,
            String status,
            int limit,
            String cursor,
            String sort) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return riskTagsPage(userId, status, limit, cursor, sort);
    }

    public AdminCursorPage.CursorPage<AmlCase> adminAmlCasesPage(
            String authorization,
            Long userId,
            String status,
            int limit,
            String cursor,
            String sort) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return amlCasesPage(userId, status, limit, cursor, sort);
    }

    public KycProfile adminUpsertKyc(String authorization,
                                     long userId,
                                     KycUpdateRequest request,
                                     AdminApprovalService.AdminRequestMetadata metadata,
                                     byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        authService.adminUser(authorization, userId);
        return upsertKyc(userId, principal.userId(), request, Instant.now());
    }

    public KycProfile userKyc(String authorization) {
        return kycRepository.find(authService.authenticateBearer(authorization).userId());
    }

    public KycDocument uploadUserKycDocument(String authorization, String documentType,
                                             org.springframework.web.multipart.MultipartFile file) {
        long userId = authService.authenticateBearer(authorization).userId();
        return kycDocumentService.upload(userId, documentType, file);
    }

    public List<KycDocument> userKycDocuments(String authorization) {
        long userId = authService.authenticateBearer(authorization).userId();
        return kycDocumentService.findForUser(userId);
    }

    public KycDocumentContent userKycDocument(String authorization, long documentId) {
        long userId = authService.authenticateBearer(authorization).userId();
        KycDocument document = kycDocumentService.requireForUser(userId, documentId);
        return new KycDocumentContent(document, kycDocumentService.read(document));
    }

    public List<KycDocument> adminKycDocuments(String authorization, long userId) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        authService.adminUser(authorization, userId);
        return kycDocumentService.findForUser(userId);
    }

    public KycDocumentContent adminKycDocument(String authorization, long userId, long documentId) {
        AuthModels.JwtPrincipal principal = authService.requireAdminPermission(
                authorization, "admin.compliance.read");
        KycDocument document = kycDocumentService.requireForAdmin(userId, documentId);
        try {
            KycDocumentContent content = new KycDocumentContent(document, kycDocumentService.read(document));
            recordDocumentAccess(principal, userId, documentId, true, null);
            return content;
        } catch (RuntimeException ex) {
            recordDocumentAccess(principal, userId, documentId, false, ex.getMessage());
            throw ex;
        }
    }

    @Transactional
    public KycProfile submitUserKyc(String authorization, KycSubmissionRequest request) {
        long userId = authService.authenticateBearer(authorization).userId();
        List<KycDocument> documents = kycDocumentService.requireSubmissionDocuments(
                userId, request.documentIds(), request.documentType());
        String submittedDocuments = kycDocumentService.references(documents);
        KycProfile profile = kycRepository.submit(userId, request, submittedDocuments, Instant.now());
        kycDocumentService.markSubmitted(userId, request.documentIds());
        return profile;
    }

    public RiskTag adminCreateRiskTag(String authorization,
                                      long userId,
                                      RiskTagCreateRequest request,
                                      AdminApprovalService.AdminRequestMetadata metadata,
                                      byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        authService.adminUser(authorization, userId);
        return createRiskTag(userId, principal.userId(), request, Instant.now());
    }

    public RiskTag adminResolveRiskTag(String authorization,
                                       long tagId,
                                       AdminApprovalService.AdminRequestMetadata metadata,
                                       byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return resolveRiskTag(tagId, principal.userId(), Instant.now());
    }

    public AmlCase adminCreateAmlCase(String authorization,
                                      long userId,
                                      AmlCaseCreateRequest request,
                                      AdminApprovalService.AdminRequestMetadata metadata,
                                      byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        authService.adminUser(authorization, userId);
        return createAmlCase(userId, principal.userId(), request, Instant.now());
    }

    public AmlCase adminUpdateAmlCaseStatus(String authorization,
                                            long caseId,
                                            AmlCaseStatusUpdateRequest request,
                                            AdminApprovalService.AdminRequestMetadata metadata,
                                            byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return updateAmlCaseStatus(caseId, principal.userId(), request, Instant.now());
    }

    private AuthModels.JwtPrincipal requireAdminWrite(
            String authorization,
            AdminApprovalService.AdminRequestMetadata metadata,
            byte[] body) {
        return approvalService.requireWrite(
                authorization,
                "admin.compliance.write",
                "gateway-admin",
                metadata,
                body);
    }

    public AdminCursorPage.CursorPage<ComplianceUserSummary> usersPage(Long userId,
                                                                       String kycStatus,
                                                                       String tagCode,
                                                                       int limit,
                                                                       String cursor,
                                                                       String sort) {
        return userProjectionRepository.usersPage(userId, kycStatus, tagCode, limit, cursor, sort);
    }

    public KycProfile upsertKyc(long userId, long adminUserId, KycUpdateRequest request, Instant now) {
        return kycRepository.upsert(userId, adminUserId, request, now);
    }

    public KycProfile kyc(long userId) {
        return kycRepository.find(userId);
    }

    private void recordDocumentAccess(AuthModels.JwtPrincipal principal,
                                      long userId,
                                      long documentId,
                                      boolean success,
                                      String errorMessage) {
        adminAuditRepository.record(new AdminAuditRepository.AdminOperationRecord(
                principal.userId(), principal.username(), principal.roles(), "gateway", "GET",
                "/api/v1/admin/compliance/users/" + userId + "/kyc/documents/" + documentId,
                null, null, null, success ? 200 : 500, null, success, errorMessage, null, null, null, Instant.now()));
    }

    public List<RiskTag> riskTags(Long userId, String status, int limit) {
        return riskTagRepository.find(userId, status, limit);
    }

    public AdminCursorPage.CursorPage<RiskTag> riskTagsPage(Long userId,
                                                            String status,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        return riskTagRepository.findPage(userId, status, limit, cursor, sort);
    }

    public RiskTag createRiskTag(long userId,
                                 long adminUserId,
                                 RiskTagCreateRequest request,
                                 Instant now) {
        return riskTagRepository.create(userId, adminUserId, request, now);
    }

    public RiskTag resolveRiskTag(long tagId, long adminUserId, Instant now) {
        return riskTagRepository.resolve(tagId, adminUserId, now);
    }

    public List<AmlCase> amlCases(Long userId, String status, int limit) {
        return amlCaseRepository.find(userId, status, limit);
    }

    public AdminCursorPage.CursorPage<AmlCase> amlCasesPage(Long userId,
                                                            String status,
                                                            int limit,
                                                            String cursor,
                                                            String sort) {
        return amlCaseRepository.findPage(userId, status, limit, cursor, sort);
    }

    public AmlCase createAmlCase(long userId,
                                 long adminUserId,
                                 AmlCaseCreateRequest request,
                                 Instant now) {
        return amlCaseRepository.create(userId, adminUserId, request, now);
    }

    public AmlCase updateAmlCaseStatus(long caseId,
                                       long adminUserId,
                                       AmlCaseStatusUpdateRequest request,
                                       Instant now) {
        return amlCaseRepository.updateStatus(caseId, adminUserId, request, now);
    }

    /**
     * 合规用户详情由服务层统一聚合，Controller 只负责协议响应映射。
     */
    public record AdminComplianceUserDetail(
            AuthenticatedUser user,
            KycProfile kyc,
            List<KycDocument> kycDocuments,
            List<RiskTag> riskTags,
            List<AmlCase> amlCases) {
    }

    public record KycDocumentContent(KycDocument document, byte[] content) {
    }
}
