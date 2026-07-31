package com.surprising.gateway.provider.auth;

import com.surprising.gateway.provider.auth.ComplianceModels.AmlCase;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseCreateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.AmlCaseStatusUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.ComplianceUserSummary;
import com.surprising.gateway.provider.auth.ComplianceModels.KycProfile;
import com.surprising.gateway.provider.auth.ComplianceModels.KycUpdateRequest;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTag;
import com.surprising.gateway.provider.auth.ComplianceModels.RiskTagCreateRequest;
import java.time.Instant;
import java.util.List;
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

    public ComplianceService(ComplianceUserProjectionRepository userProjectionRepository,
                             ComplianceKycRepository kycRepository,
                             ComplianceRiskTagRepository riskTagRepository,
                             ComplianceAmlCaseRepository amlCaseRepository) {
        this.userProjectionRepository = userProjectionRepository;
        this.kycRepository = kycRepository;
        this.riskTagRepository = riskTagRepository;
        this.amlCaseRepository = amlCaseRepository;
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
}
