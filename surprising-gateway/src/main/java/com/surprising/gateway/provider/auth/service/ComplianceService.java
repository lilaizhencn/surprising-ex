package com.surprising.gateway.provider.auth.service;

import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.client.KycProvider;
import com.surprising.gateway.provider.auth.client.KycProviderRegistry;
import com.surprising.gateway.provider.auth.model.AuthModels;
import com.surprising.gateway.provider.auth.model.ComplianceModels;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceAmlCaseRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceKycRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceRiskTagRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceUserProjectionRepository;
import com.surprising.gateway.provider.auth.repository.CountryRepository;
import com.surprising.gateway.provider.auth.utils.ComplianceValidation;

import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;

import com.surprising.gateway.provider.auth.model.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.model.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycDocument;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycSubmissionResponse;
import com.surprising.gateway.provider.auth.model.ComplianceModels.KycProviderInfo;
import com.surprising.gateway.provider.auth.model.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.model.ComplianceModels.RiskTagCreateRequest;
import com.surprising.gateway.provider.auth.model.AuthModels.AuthenticatedUser;
import com.surprising.gateway.provider.auth.repository.KycProviderEventRepository;
import com.surprising.gateway.provider.service.KycDocumentService;
import com.surprising.gateway.provider.service.SecurityNotificationService;
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
    private final KycProviderEventRepository providerEvents;
    private final ComplianceRiskTagRepository riskTagRepository;
    private final ComplianceAmlCaseRepository amlCaseRepository;
    private final AuthService authService;
    private final AdminApprovalService approvalService;
    private final KycDocumentService kycDocumentService;
    private final AdminAuditRepository adminAuditRepository;
    private final AuthPersistenceService authPersistence;
    private final SecurityNotificationService notifications;
    private final KycProviderRegistry kycProviders;
    private final CountryRepository countries;

    public ComplianceService(ComplianceUserProjectionRepository userProjectionRepository,
                             ComplianceKycRepository kycRepository,
                             KycProviderEventRepository providerEvents,
                             ComplianceRiskTagRepository riskTagRepository,
                             ComplianceAmlCaseRepository amlCaseRepository,
                             AuthService authService,
                             AdminApprovalService approvalService,
                             KycDocumentService kycDocumentService,
                             AdminAuditRepository adminAuditRepository,
                             AuthPersistenceService authPersistence,
                             SecurityNotificationService notifications,
                             KycProviderRegistry kycProviders, CountryRepository countries) {
        this.userProjectionRepository = userProjectionRepository;
        this.kycRepository = kycRepository;
        this.providerEvents = providerEvents;
        this.riskTagRepository = riskTagRepository;
        this.amlCaseRepository = amlCaseRepository;
        this.authService = authService;
        this.approvalService = approvalService;
        this.kycDocumentService = kycDocumentService;
        this.adminAuditRepository = adminAuditRepository;
        this.authPersistence = authPersistence;
        this.notifications = notifications;
        this.kycProviders = kycProviders;
        this.countries = countries;
    }

    public AdminCursorPage.CursorPage<ComplianceUserSummary> adminUsersPage(
            String authorization,
            Long userId,
            String kycStatus,
            String tagCode,
            AuthPageQuery pageQuery) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return usersPage(userId, kycStatus, tagCode, pageQuery);
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
            AuthPageQuery pageQuery) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return riskTagsPage(userId, status, pageQuery);
    }

    public AdminCursorPage.CursorPage<AmlCase> adminAmlCasesPage(
            String authorization,
            Long userId,
            String status,
            AuthPageQuery pageQuery) {
        authService.requireAdminPermission(authorization, "admin.compliance.read");
        return amlCasesPage(userId, status, pageQuery);
    }

    @Transactional
    public KycProfile adminUpsertKyc(String authorization,
                                     long userId,
                                     KycUpdateRequest request,
                                     AdminRequestMetadata metadata,
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
        return new KycProviderInfo(kycProviders.selectedProvider(), kycProviders.simulationEnabled(), kycProviders.verificationOptions());
    }

    public KycProvider.KycProviderSession refreshUserKycSession(String authorization) {
        long userId = authService.authenticateBearer(authorization).userId();
        KycProfile profile = kycRepository.find(userId);
        if (profile == null || !"PENDING".equals(profile.status())
                || profile.provider() == null || "SELF".equalsIgnoreCase(profile.provider())) {
            throw new IllegalStateException("there is no active KYC provider session");
        }
        return kycProviders.refreshSession(profile, kycRepository.providerRedirectUrl(userId));
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
        final KycDocumentContent content;
        try {
            content = new KycDocumentContent(document, kycDocumentService.read(document));
        } catch (RuntimeException ex) {
            try {
                recordDocumentAccess(principal, userId, documentId, false, ex.getClass().getSimpleName());
            } catch (RuntimeException auditFailure) {
                ex.addSuppressed(auditFailure);
            }
            throw ex;
        }
        recordDocumentAccess(principal, userId, documentId, true, null);
        return content;
    }

    @Transactional
    public KycSubmissionResponse submitUserKyc(String authorization, KycSubmissionRequest request) {
        long userId = authService.authenticateBearer(authorization).userId();
        if (!countries.supported(request.country())) throw new IllegalArgumentException("select a supported country");
        Instant startedAt = Instant.now();
        KycProfile current = kycRepository.lockForSubmission(userId, startedAt);
        if (current != null && ("PENDING".equals(current.status()) || ("VERIFIED".equals(current.status())
                && (current.expiresAt() == null || current.expiresAt().isAfter(startedAt))))) {
            throw new IllegalStateException("verification is already pending or complete");
        }
        java.time.LocalDate today = java.time.LocalDate.now(java.time.ZoneOffset.UTC);
        boolean hasDocuments = request.documentIds() != null && !request.documentIds().isEmpty();
        if (!List.of("ID_CARD", "PASSPORT", "DRIVING_LICENSE", "RESIDENCE_PERMIT").contains(request.documentType())) {
            throw new IllegalArgumentException("select a supported identity document");
        }
        if (hasDocuments) {
            if (request.documentExpiresOn() == null || request.documentExpiresOn().isBefore(today)) {
                throw new IllegalArgumentException("identity document must not be expired");
            }
            if ("ID_CARD".equals(request.documentType()) &&
                    (request.documentIssuedOn() == null || request.documentIssuedOn().isAfter(today) ||
                            request.documentIssuedOn().isAfter(request.documentExpiresOn()))) {
                throw new IllegalArgumentException("identity card issue date must be valid and before its expiry date");
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
                ComplianceValidation.provider(session.provider()),
                ComplianceValidation.providerReference(ComplianceValidation.provider(session.provider()), session.providerReference()), request.submittedDocuments(),
                "SELF".equals(session.provider()) ? request.faceVerificationStatus() : "PENDING", request.documentIds(),
                request.documentExpiresOn(), request.addressIssuedOn(), request.documentIssuedOn());
        String submittedDocuments = kycDocumentService.references(documents, request.documentExpiresOn(),
                request.addressIssuedOn(), request.documentIssuedOn());
        KycProfile profile = kycRepository.submit(userId, providerRequest, submittedDocuments, Instant.now());
        kycRepository.saveProviderSession(userId, session.redirectUrl(), startedAt);
        if (hasDocuments && request.documentExpiresOn() != null) {
            kycRepository.updateExpiry(userId, request.documentExpiresOn().plusDays(1).atStartOfDay(java.time.ZoneOffset.UTC).toInstant());
            profile = kycRepository.find(userId);
        }
        if (request.documentIds() != null && !request.documentIds().isEmpty()) kycDocumentService.markSubmitted(userId, request.documentIds());
        return new KycSubmissionResponse(profile, session);
    }

    @Transactional
    public KycProfile applyProviderCallback(String provider, Map<String, String> headers, byte[] body) {
        KycProvider.KycProviderResult result = kycProviders.verifyCallback(provider, headers, body)
                .orElseThrow(() -> new IllegalArgumentException("KYC callback could not be verified"));
        Long userId;
        try { userId = Long.valueOf(result.externalUserId().replaceFirst(":BUSINESS$", "")); }
        catch (RuntimeException ex) { throw new IllegalArgumentException("KYC callback user reference is invalid", ex); }
        if (result.eventKey() == null || result.eventKey().isBlank()) throw new IllegalArgumentException("provider event key is required");
        if (!providerEvents.record(provider, result.eventKey(), Instant.now())) return kycRepository.find(userId);
        KycProfile previous = kycRepository.findForUpdate(userId);
        if (previous == null || !provider.equalsIgnoreCase(previous.provider())
                || result.providerReference() == null || !result.providerReference().equals(previous.providerReference())) {
            throw new IllegalArgumentException("KYC callback does not match the current verification session");
        }
        if ("SUMSUB".equalsIgnoreCase(provider) && "BUSINESS".equals(previous.applicantType()) != result.externalUserId().endsWith(":BUSINESS")) {
            throw new IllegalArgumentException("KYC callback applicant type does not match the current verification session");
        }
        kycProviders.requireCallbackLevel(previous, result);
        if (result.occurredAt() == null || result.occurredAt().isAfter(Instant.now().plusSeconds(300))) {
            throw new IllegalArgumentException("KYC callback timestamp is invalid");
        }
        // 人工决定具有明确审核主体；迟到待审事件不能撤销终态，新申请以会话起始时间隔离旧回执。
        if ((previous.reviewedByUserId() != null && !List.of("REJECTED","EXPIRED").contains(result.status()))
                || (!"PENDING".equals(previous.status()) && "PENDING".equals(result.status()))
                || !kycRepository.acceptProviderEvent(userId, result.occurredAt())) return previous;
        if (result.evidenceExpiresAt() != null && (previous.expiresAt() == null || result.evidenceExpiresAt().isBefore(previous.expiresAt()))) {
            kycRepository.updateExpiry(userId,result.evidenceExpiresAt());
            previous = kycRepository.findForUpdate(userId);
        }
        KycProfile updated = applyProviderResult(previous, provider, result.status(), result.rejectionReason(), Instant.now());
        if (!kycProviders.simulationEnabled()) enqueueKycTransition(previous, updated);
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
        if (!providerEvents.record(selected,
                "SIMULATION:" + userId + ":" + java.util.Objects.toString(previous.providerReference(), "manual")
                        + ":" + previous.updatedAt().toEpochMilli(), Instant.now())) {
            throw new IllegalStateException("the simulated KYC result was already submitted");
        }
        KycProfile locked = kycRepository.findForUpdate(userId);
        if (!java.util.Objects.equals(previous, locked)) throw new IllegalStateException("the KYC session changed before its result was saved");
        String status = switch (decision) { case "APPROVED" -> "VERIFIED"; case "REJECTED" -> "REJECTED"; default -> "PENDING"; };
        String reason = "REJECTED".equals(status) ? "Simulated provider rejection" : null;
        KycProfile updated = applyProviderResult(locked, selected, status, reason, Instant.now());
        return updated;
    }

    private KycProfile applyProviderResult(KycProfile current, String provider, String rawStatus,
                                           String rejectionReason, Instant now) {
        // 提供方允许的结果及其与人脸状态的联动属于业务状态机；当前行已由调用入口加锁。
        String status = ComplianceValidation.kycStatus(rawStatus);
        if (!List.of("PENDING", "VERIFIED", "REJECTED", "EXPIRED").contains(status)) {
            throw new IllegalArgumentException("provider KYC status is invalid");
        }
        String faceStatus = current.faceVerificationStatus();
        if (!"NOT_REQUIRED".equals(faceStatus)) {
            faceStatus = switch (status) {
                case "VERIFIED" -> "VERIFIED";
                case "REJECTED" -> "FAILED";
                default -> faceStatus;
            };
        }
        Instant expiry = current.expiresAt();
        if ("VERIFIED".equals(status)) {
            Instant validityEnd = now.plus(kycProviders.verificationValidity());
            if (expiry == null || expiry.isAfter(validityEnd)) expiry = validityEnd;
            if (!expiry.isAfter(now)) status = "EXPIRED";
        }
        return kycRepository.applyProviderResult(current.userId(), provider, status,
                "REJECTED".equals(status) ? ComplianceValidation.blankToNull(rejectionReason) : null,
                "PENDING".equals(status) ? null : now, faceStatus, now, expiry);
    }

    private void enqueueKycTransition(KycProfile previous, KycProfile updated) {
        if (previous != null && !previous.status().equals(updated.status())
                && List.of("VERIFIED", "REJECTED").contains(updated.status())) {
            authPersistence.user(updated.userId()).map(AuthenticatedUser::email).filter(email -> email != null && !email.isBlank())
                    .ifPresent(email -> notifications.kycResult(updated, email));
        }
    }

    @Transactional
    public RiskTag adminCreateRiskTag(String authorization,
                                      long userId,
                                      RiskTagCreateRequest request,
                                      AdminRequestMetadata metadata,
                                      byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        authService.adminUser(authorization, userId);
        return createRiskTag(userId, principal.userId(), request, Instant.now());
    }

    @Transactional
    public RiskTag adminResolveRiskTag(String authorization,
                                       long tagId,
                                       AdminRequestMetadata metadata,
                                       byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return resolveRiskTag(tagId, principal.userId(), Instant.now());
    }

    @Transactional
    public AmlCase adminCreateAmlCase(String authorization,
                                      long userId,
                                      AmlCaseCreateRequest request,
                                      AdminRequestMetadata metadata,
                                      byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        authService.adminUser(authorization, userId);
        return createAmlCase(userId, principal.userId(), request, Instant.now());
    }

    @Transactional
    public AmlCase adminUpdateAmlCaseStatus(String authorization,
                                            long caseId,
                                            AmlCaseStatusUpdateRequest request,
                                            AdminRequestMetadata metadata,
                                            byte[] body) {
        AuthModels.JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return updateAmlCaseStatus(caseId, principal.userId(), request, Instant.now());
    }

    private AuthModels.JwtPrincipal requireAdminWrite(
            String authorization,
            AdminRequestMetadata metadata,
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
                                                                       AuthPageQuery pageQuery) {
        return userProjectionRepository.usersPage(userId, kycStatus, tagCode, pageQuery);
    }

    @Transactional
    public KycProfile upsertKyc(long userId, long adminUserId, KycUpdateRequest request, Instant now) {
        KycProfile previous = kycRepository.findForUpdate(userId);
        if ("REJECTED".equals(request.status()) && (request.rejectionReason() == null || request.rejectionReason().isBlank())) {
            throw new IllegalArgumentException("a rejection reason is required");
        }
        if ("VERIFIED".equals(request.status()) && (request.expiresAt() == null || !request.expiresAt().isAfter(now))) {
            throw new IllegalArgumentException("verified KYC requires a future expiry date");
        }
        if ("VERIFIED".equals(request.status()) && (previous == null || "NONE".equals(request.kycLevel())
                || (!"SELF".equals(request.provider()) && (previous.providerReference() == null || previous.providerReference().isBlank())))) {
            throw new IllegalArgumentException("approval requires a submitted identity verification application");
        }
        if ("VERIFIED".equals(request.status()) && "SELF".equals(request.provider())
                && (previous == null || "[]".equals(previous.submittedDocuments()))) {
            throw new IllegalArgumentException("manual approval requires submitted identity evidence");
        }
        if ("VERIFIED".equals(request.status()) && previous != null) {
            if (!java.util.Objects.equals(previous.provider(),request.provider())) {
                throw new IllegalArgumentException("approval must use the provider of the submitted application");
            }
            if ("SELF".equals(request.provider())) kycDocumentService.requireManualApprovalEvidence(previous,request.documentType(),request.kycLevel());
            else if (!previous.kycLevel().equals(request.kycLevel())
                    || !java.util.Objects.equals(previous.providerReference(), request.providerReference())) {
                throw new IllegalArgumentException("approval cannot change the level or session verified by the provider");
            }
        }
        if ("VERIFIED".equals(request.status()) && previous != null && previous.expiresAt() != null
                && request.expiresAt().isAfter(previous.expiresAt())) {
            throw new IllegalArgumentException("approval expiry cannot exceed submitted identity evidence validity");
        }
        KycProfile updated = kycRepository.upsert(userId, adminUserId, request, now);
        enqueueKycTransition(previous, updated);
        return updated;
    }

    public KycProfile kyc(long userId) {
        return kycRepository.find(userId);
    }

    public void requireWithdrawalEligibility(long userId) {
        Instant restrictedUntil = authService.withdrawalRestrictedUntil(userId);
        if (restrictedUntil != null && restrictedUntil.isAfter(Instant.now())) {
            throw new WithdrawalSecurityHoldException(restrictedUntil);
        }
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
        adminAuditRepository.recordRequired(new com.surprising.gateway.provider.auth.model.AdminOperationRecord(
                principal.userId(), principal.username(), principal.roles(), "gateway", "GET",
                "/api/v1/admin/compliance/users/" + userId + "/kyc/documents/" + documentId,
                null, null, null, success ? 200 : 500, null, success, errorMessage, null, null, Instant.now()));
    }

    public List<RiskTag> riskTags(Long userId, String status, int limit) {
        return riskTagRepository.find(userId, status, limit);
    }

    public AdminCursorPage.CursorPage<RiskTag> riskTagsPage(Long userId,
                                                            String status,
                                                            AuthPageQuery pageQuery) {
        return riskTagRepository.findPage(userId, status, pageQuery);
    }

    public RiskTag createRiskTag(long userId,
                                 long adminUserId,
                                 RiskTagCreateRequest request,
                                 Instant now) {
        try {
            return riskTagRepository.create(userId, adminUserId, request, now);
        } catch (org.springframework.dao.DuplicateKeyException duplicate) {
            throw new IllegalArgumentException("active risk tag already exists", duplicate);
        }
    }

    public RiskTag resolveRiskTag(long tagId, long adminUserId, Instant now) {
        return riskTagRepository.resolve(tagId, adminUserId, now)
                .orElseThrow(() -> new IllegalArgumentException("risk tag not found"));
    }

    public List<AmlCase> amlCases(Long userId, String status, int limit) {
        return amlCaseRepository.find(userId, status, limit);
    }

    public AdminCursorPage.CursorPage<AmlCase> amlCasesPage(Long userId,
                                                            String status,
                                                            AuthPageQuery pageQuery) {
        return amlCaseRepository.findPage(userId, status, pageQuery);
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
        Instant closedAt = List.of("CLEARED", "CLOSED").contains(request.status()) ? now : null;
        return amlCaseRepository.updateStatus(caseId, adminUserId, request.status(), request.riskScore(), closedAt, now)
                .orElseThrow(() -> new IllegalArgumentException("aml case not found"));
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

    public static class WithdrawalSecurityHoldException extends IllegalStateException {
        private final Instant restrictedUntil;
        public WithdrawalSecurityHoldException(Instant restrictedUntil) {
            super("withdrawals are temporarily restricted until " + restrictedUntil);
            this.restrictedUntil = restrictedUntil;
        }
        public Instant restrictedUntil() { return restrictedUntil; }
    }
}
