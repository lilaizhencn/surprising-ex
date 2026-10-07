package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.ComplianceModels.KycSubmissionResponse;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProviderInfo;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTagCreateRequest;
import com.surprising.gateway.provider.auth.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.service.KycDocumentService;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
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
    private final AuthPersistenceService authPersistence;
    private final EmailMessageSender emailMessageSender;
    private final KycProviderRegistry kycProviders;
    private final CountryRepository countries;

    public ComplianceService(ComplianceUserProjectionRepository userProjectionRepository,
                             ComplianceKycRepository kycRepository,
                             ComplianceRiskTagRepository riskTagRepository,
                             ComplianceAmlCaseRepository amlCaseRepository,
                             AuthService authService,
                             AdminApprovalService approvalService,
                             KycDocumentService kycDocumentService,
                             AdminAuditRepository adminAuditRepository,
                             AuthPersistenceService authPersistence,
                             EmailMessageSender emailMessageSender,
                             KycProviderRegistry kycProviders, CountryRepository countries) {
        this.userProjectionRepository = userProjectionRepository;
        this.kycRepository = kycRepository;
        this.riskTagRepository = riskTagRepository;
        this.amlCaseRepository = amlCaseRepository;
        this.authService = authService;
        this.approvalService = approvalService;
        this.kycDocumentService = kycDocumentService;
        this.adminAuditRepository = adminAuditRepository;
        this.authPersistence = authPersistence;
        this.emailMessageSender = emailMessageSender;
        this.kycProviders = kycProviders;
        this.countries = countries;
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

    public List<CountryRepository.Country> countries(String authorization) {
        authService.authenticateBearer(authorization);
        return countries.enabled();
    }

    public void deleteKycDraft(String authorization, long documentId) {
        kycDocumentService.deleteDraft(authService.authenticateBearer(authorization).userId(), documentId);
    }

    public KycProfile userKyc(String authorization) {
        return kycRepository.find(authService.authenticateBearer(authorization).userId());
    }

    public KycProviderInfo userKycProviderInfo(String authorization) {
        authService.authenticateBearer(authorization);
        return new KycProviderInfo(kycProviders.selectedProvider(), kycProviders.simulationEnabled());
    }

    public KycProvider.KycProviderSession refreshUserKycSession(String authorization) {
        long userId = authService.authenticateBearer(authorization).userId();
        KycProfile profile = kycRepository.find(userId);
        if (profile == null || !"PENDING".equals(profile.status())
                || profile.provider() == null || "SELF".equalsIgnoreCase(profile.provider())) {
            throw new IllegalStateException("there is no active KYC provider session");
        }
        return kycProviders.refreshSession(profile.provider(), userId, profile.providerReference());
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
    public KycSubmissionResponse submitUserKyc(String authorization, KycSubmissionRequest request) {
        long userId = authService.authenticateBearer(authorization).userId();
        if (!countries.supported(request.country())) throw new IllegalArgumentException("select a supported country");
        KycProfile current = kycRepository.find(userId);
        if (current != null && ("VERIFIED".equals(current.status()) || "PENDING".equals(current.status()))) {
            throw new IllegalStateException("verification is already pending or complete");
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        boolean hasDocuments = request.documentIds() != null && !request.documentIds().isEmpty();
        if (hasDocuments) {
            if (!List.of("ID_CARD", "PASSPORT").contains(request.documentType())) throw new IllegalArgumentException("select an identity card or passport");
            if (request.documentExpiresOn() == null || request.documentExpiresOn().isBefore(today)) {
                throw new IllegalArgumentException("identity document must not be expired");
            }
            if (List.of("STANDARD", "ENHANCED").contains(request.kycLevel())
                    && (request.addressIssuedOn() == null || request.addressIssuedOn().isBefore(today.minusMonths(3))
                    || request.addressIssuedOn().isAfter(today))) {
                throw new IllegalArgumentException("address proof must be issued within the last three months");
            }
        }
        if (!hasDocuments && "SELF".equals(kycProviders.selectedProvider())) throw new IllegalArgumentException("upload all required KYC documents");
        // External SDK sessions collect and validate evidence in the provider flow.
        List<KycDocument> documents = hasDocuments ? kycDocumentService.requireSubmissionDocuments(userId, request.documentIds(), request.documentType(),
                request.applicantType(), request.kycLevel(), request.faceVerificationStatus()) : List.of();
        KycProvider.KycProviderSession session = kycProviders.start(userId, request, documents);
        ComplianceModels.KycSubmissionRequest providerRequest = new ComplianceModels.KycSubmissionRequest(
                request.applicantType(), request.kycLevel(), request.country(), request.documentType(),
                session.provider(), session.providerReference(), request.submittedDocuments(),
                "SELF".equals(session.provider()) ? request.faceVerificationStatus() : "PENDING", request.documentIds());
        String submittedDocuments = kycDocumentService.references(documents, request.documentExpiresOn(), request.addressIssuedOn());
        KycProfile profile = kycRepository.submit(userId, providerRequest, submittedDocuments, Instant.now());
        if (request.documentIds() != null && !request.documentIds().isEmpty()) kycDocumentService.markSubmitted(userId, request.documentIds());
        return new KycSubmissionResponse(profile, session);
    }

    @Transactional
    public KycProfile applyProviderCallback(String provider, Map<String, String> headers, byte[] body) {
        KycProvider.KycProviderResult result = kycProviders.verifyCallback(provider, headers, body)
                .orElseThrow(() -> new IllegalArgumentException("KYC callback could not be verified"));
        Long userId;
        try { userId = Long.valueOf(result.externalUserId()); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("KYC callback user reference is invalid", ex); }
        if (!kycRepository.recordProviderEvent(provider, result.eventKey(), Instant.now())) return kycRepository.find(userId);
        KycProfile previous = kycRepository.find(userId);
        if (previous == null || !provider.equalsIgnoreCase(previous.provider())
                || (result.providerReference() != null && !result.providerReference().equals(previous.providerReference()))) {
            throw new IllegalArgumentException("KYC callback does not match the current verification session");
        }
        KycProfile updated = kycRepository.applyProviderResult(userId, provider, result.status(),
                result.rejectionReason(), Instant.now());
        notifyKycTransition(previous, updated);
        return updated;
    }

    @Transactional
    public KycProfile completeKycSimulation(String authorization, String decision) {
        long userId = authService.authenticateBearer(authorization).userId();
        if (!kycProviders.simulationEnabled()) throw new IllegalStateException("KYC simulation is disabled");
        if (!List.of("APPROVED", "REJECTED", "MANUAL_REVIEW").contains(decision)) throw new IllegalArgumentException("decision must be APPROVED, REJECTED or MANUAL_REVIEW");
        KycProfile previous = kycRepository.find(userId);
        String selected = kycProviders.selectedProvider();
        if (previous == null || !selected.equalsIgnoreCase(previous.provider()) || !"PENDING".equals(previous.status())) {
            throw new IllegalStateException("there is no pending simulated KYC session");
        }
        if (!kycRepository.recordProviderEvent(selected,
                "SIMULATION:" + userId + ":" + java.util.Objects.toString(previous.providerReference(), "manual")
                        + ":" + previous.updatedAt().toEpochMilli(), Instant.now())) {
            throw new IllegalStateException("the simulated KYC result was already submitted");
        }
        String status = switch (decision) { case "APPROVED" -> "VERIFIED"; case "REJECTED" -> "REJECTED"; default -> "PENDING"; };
        String reason = "REJECTED".equals(status) ? "Simulated provider rejection" : null;
        KycProfile updated = kycRepository.applyProviderResult(userId, selected, status, reason, Instant.now());
        notifyKycTransition(previous, updated);
        return updated;
    }

    private void notifyKycTransition(KycProfile previous, KycProfile updated) {
        if (kycProviders.simulationEnabled()) return;
        if (previous != null && "PENDING".equals(previous.status())
                && List.of("VERIFIED", "REJECTED").contains(updated.status())) {
            authPersistence.user(updated.userId()).map(AuthenticatedUser::email).filter(email -> email != null && !email.isBlank())
                    .ifPresent(email -> { try { emailMessageSender.send(email, "Surprising identity verification update",
                            "Your identity verification has been " + updated.status().toLowerCase(Locale.ROOT)
                                    + (updated.rejectionReason() == null ? "." : ": " + updated.rejectionReason())); }
                        catch (RuntimeException ignored) { } });
        }
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
        KycProfile previous = kycRepository.find(userId);
        KycProfile updated = kycRepository.upsert(userId, adminUserId, request, now);
        if (previous != null && "PENDING".equals(previous.status())
                && List.of("VERIFIED", "REJECTED").contains(updated.status())) {
            authPersistence.user(userId).map(AuthenticatedUser::email)
                    .filter(email -> email != null && !email.isBlank())
                    .ifPresent(email -> {
                        try {
                            emailMessageSender.send(email, "Surprising identity verification update",
                                    "Your identity verification has been " + updated.status().toLowerCase(Locale.ROOT)
                                            + (updated.rejectionReason() == null ? "."
                                            : ". Reason: " + updated.rejectionReason()));
                        } catch (RuntimeException ignored) {
                            // KYC review state is durable even if the notification provider is unavailable.
                        }
                    });
        }
        return updated;
    }

    public KycProfile kyc(long userId) {
        return kycRepository.find(userId);
    }

    public void requireWithdrawalEligibility(long userId) {
        List<RiskTag> activeTags = riskTags(userId, "ACTIVE", 100);
        boolean blockedByRiskTag = activeTags.stream().anyMatch(tag ->
                "HIGH".equalsIgnoreCase(tag.severity()) || "CRITICAL".equalsIgnoreCase(tag.severity()));
        if (blockedByRiskTag) {
            throw new IllegalStateException("withdrawals are restricted by active account risk controls");
        }
        boolean blockedByAmlCase = amlCases(userId, null, 200).stream().anyMatch(caseRecord ->
                switch (caseRecord.status().toUpperCase(Locale.ROOT)) {
                    case "OPEN", "REVIEWING", "ESCALATED", "RESTRICTED" -> true;
                    default -> false;
                });
        if (blockedByAmlCase) {
            throw new IllegalStateException("withdrawals are restricted by an open compliance case");
        }
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
