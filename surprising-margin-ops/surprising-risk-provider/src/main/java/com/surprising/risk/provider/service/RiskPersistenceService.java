package com.surprising.risk.provider.service;

import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.provider.repository.RiskAccountSnapshotRepository;
import com.surprising.risk.provider.repository.RiskLiquidationCandidateRepository;
import com.surprising.risk.provider.repository.RiskLiquidationCandidateRepository.LiquidationCandidateWrite;
import com.surprising.risk.provider.repository.RiskPositionSnapshotRepository;
import com.surprising.risk.provider.repository.RiskPositionSnapshotRepository.PositionSnapshotWrite;
import com.surprising.risk.provider.repository.RiskRuleRepository;
import com.surprising.risk.provider.repository.RiskRuleRepository.RiskRuleOverride;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.springframework.stereotype.Service;

/** 在风险业务事务内聚合账户快照、持仓快照、强平候选和管理规则的单表仓储。 */
@Service
public class RiskPersistenceService {

    private final RiskAccountSnapshotRepository accountSnapshotRepository;
    private final RiskPositionSnapshotRepository positionSnapshotRepository;
    private final RiskLiquidationCandidateRepository candidateRepository;
    private final RiskRuleRepository ruleRepository;

    public RiskPersistenceService(RiskAccountSnapshotRepository accountSnapshotRepository,
                                  RiskPositionSnapshotRepository positionSnapshotRepository,
                                  RiskLiquidationCandidateRepository candidateRepository,
                                  RiskRuleRepository ruleRepository) {
        this.accountSnapshotRepository = accountSnapshotRepository;
        this.positionSnapshotRepository = positionSnapshotRepository;
        this.candidateRepository = candidateRepository;
        this.ruleRepository = ruleRepository;
    }

    public void saveAccountSnapshots(List<RiskAccountSnapshotResponse> snapshots) {
        accountSnapshotRepository.saveAll(snapshots);
    }

    public void savePositionSnapshots(List<PositionSnapshotWrite> snapshots) {
        positionSnapshotRepository.saveAll(snapshots);
    }

    public Set<Long> createLiquidationCandidates(List<LiquidationCandidateWrite> candidates) {
        return candidateRepository.createAll(candidates);
    }

    public Optional<RiskAccountSnapshotResponse> latestAccount(
            long userId, String accountType, String settleAsset) {
        return accountSnapshotRepository.latest(userId, accountType, settleAsset);
    }

    public List<RiskPositionSnapshotResponse> latestPositions(long userId) {
        return positionSnapshotRepository.latest(userId);
    }

    public List<LiquidationCandidateResponse> liquidationCandidates(
            LiquidationCandidateStatus status, int limit) {
        return candidateRepository.findByStatus(status, limit);
    }

    public AdminCursorPage.CursorPage<LiquidationCandidateResponse> liquidationCandidatesPage(
            LiquidationCandidateStatus status, int limit, String cursor, String sort) {
        return candidateRepository.page(status, limit, cursor, sort);
    }

    public List<RiskRuleOverride> riskRuleOverrides() {
        return ruleRepository.findAll();
    }

    public RiskRuleOverride upsertRiskRuleOverride(String ruleCode,
                                                   String ruleName,
                                                   String ruleType,
                                                   boolean enabled,
                                                   Long warningMarginRatioPpm,
                                                   Long liquidationMarginRatioPpm,
                                                   Long scanDelayMs,
                                                   Integer scanBatchSize,
                                                   String adminUserId,
                                                   String reason,
                                                   Instant now) {
        return ruleRepository.upsert(ruleCode, ruleName, ruleType, enabled, warningMarginRatioPpm,
                liquidationMarginRatioPpm, scanDelayMs, scanBatchSize, adminUserId, reason, now);
    }
}
