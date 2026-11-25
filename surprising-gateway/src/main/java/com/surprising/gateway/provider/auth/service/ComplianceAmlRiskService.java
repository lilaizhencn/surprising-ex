package com.surprising.gateway.provider.auth.service;


import com.surprising.gateway.provider.auth.model.JwtPrincipal;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.model.AuthPageQuery;
import com.surprising.gateway.provider.auth.model.AmlCase;
import com.surprising.gateway.provider.auth.model.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.model.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.model.RiskTag;
import com.surprising.gateway.provider.auth.model.RiskTagCreateRequest;
import com.surprising.gateway.provider.auth.repository.ComplianceAmlCaseRepository;
import com.surprising.gateway.provider.auth.repository.ComplianceRiskTagRepository;
import com.surprising.trading.api.utils.AdminCursorPage;
import java.time.Instant;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 负责合规风险标签（RiskTag）与反洗钱案件（AML Case）的查询及管理操作。
 */
@Service
public class ComplianceAmlRiskService {

    private final ComplianceRiskTagRepository riskTagRepository;
    private final ComplianceAmlCaseRepository amlCaseRepository;
    private final AdminAuthService adminAuthService;
    private final AdminApprovalService approvalService;

    public ComplianceAmlRiskService(ComplianceRiskTagRepository riskTagRepository,
                                    ComplianceAmlCaseRepository amlCaseRepository,
                                    AdminAuthService adminAuthService,
                                    AdminApprovalService approvalService) {
        this.riskTagRepository = riskTagRepository;
        this.amlCaseRepository = amlCaseRepository;
        this.adminAuthService = adminAuthService;
        this.approvalService = approvalService;
    }

    @Transactional
    public RiskTag adminCreateRiskTag(String authorization,
                                      long userId,
                                      RiskTagCreateRequest request,
                                      AdminRequestMetadata metadata,
                                      byte[] body) {
        JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        adminAuthService.adminUser(authorization, userId);
        return createRiskTag(userId, principal.userId(), request, Instant.now());
    }

    @Transactional
    public RiskTag adminResolveRiskTag(String authorization,
                                       long tagId,
                                       AdminRequestMetadata metadata,
                                       byte[] body) {
        JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return resolveRiskTag(tagId, principal.userId(), Instant.now());
    }

    @Transactional
    public AmlCase adminCreateAmlCase(String authorization,
                                      long userId,
                                      AmlCaseCreateRequest request,
                                      AdminRequestMetadata metadata,
                                      byte[] body) {
        JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        adminAuthService.adminUser(authorization, userId);
        return createAmlCase(userId, principal.userId(), request, Instant.now());
    }

    @Transactional
    public AmlCase adminUpdateAmlCaseStatus(String authorization,
                                            long caseId,
                                            AmlCaseStatusUpdateRequest request,
                                            AdminRequestMetadata metadata,
                                            byte[] body) {
        JwtPrincipal principal = requireAdminWrite(authorization, metadata, body);
        return updateAmlCaseStatus(caseId, principal.userId(), request, Instant.now());
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
        } catch (DuplicateKeyException duplicate) {
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

    private JwtPrincipal requireAdminWrite(String authorization,
                                                      AdminRequestMetadata metadata,
                                                      byte[] body) {
        return approvalService.requireWrite(
                authorization,
                "admin.compliance.write",
                "gateway-admin",
                metadata,
                body);
    }
}
