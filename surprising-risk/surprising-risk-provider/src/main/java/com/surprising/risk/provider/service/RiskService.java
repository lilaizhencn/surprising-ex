package com.surprising.risk.provider.service;

import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.trading.api.model.MarginMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class RiskService {

    private static final Logger log = LoggerFactory.getLogger(RiskService.class);

    private final ObjectMapper objectMapper;
    private final RiskProperties properties;
    private final RiskRepository riskRepository;
    private final RiskSequenceRepository sequenceRepository;
    private final RiskOutboxRepository outboxRepository;
    private final TransactionTemplate transactionTemplate;
    private final String nodeId;

    public RiskService(ObjectMapper objectMapper,
                       RiskProperties properties,
                       RiskRepository riskRepository,
                       RiskSequenceRepository sequenceRepository,
                       RiskOutboxRepository outboxRepository,
                       PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.riskRepository = riskRepository;
        this.sequenceRepository = sequenceRepository;
        this.outboxRepository = outboxRepository;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    @Scheduled(fixedDelayString = "${surprising.risk.calculation.scan-delay-ms:1000}")
    public void scan() {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        int batchSize = Math.max(1, properties.getCalculation().getScanBatchSize());
        RiskGroupKey after = null;
        while (true) {
            List<RiskGroupKey> groups = riskRepository.riskGroups(properties.getCalculation().getMaxMarkAge(),
                    after, batchSize);
            if (groups.isEmpty()) {
                return;
            }
            for (RiskGroupKey key : groups) {
                after = key;
                try {
                    scanRiskGroup(key);
                } catch (Exception ex) {
                    log.error("Failed to scan risk group userId={} settleAsset={}: {}",
                            key.userId(), key.settleAsset(), ex.getMessage(), ex);
                }
            }
        }
    }

    /**
     * Event-driven fast path used by account position events. The scheduled scanner is still the authoritative
     * fallback, but this method cuts liquidation latency after fills by scanning only the affected user/settle group.
     */
    public void scanPositionUpdate(long userId, String symbol, MarginMode marginMode, long instrumentVersion) {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        PositionRiskTarget target = riskRepository.riskTargetForPositionEvent(userId, normalizeSymbol(symbol),
                MarginMode.defaultIfNull(marginMode), instrumentVersion).orElse(null);
        if (target == null) {
            log.debug("Position update did not resolve to a risk group userId={} symbol={} version={}",
                    userId, symbol, instrumentVersion);
            return;
        }
        scanRiskGroup(target.riskGroupKey(), target);
    }

    public void scanPositionUpdate(long userId, String symbol, long instrumentVersion) {
        scanPositionUpdate(userId, symbol, MarginMode.CROSS, instrumentVersion);
    }

    private boolean ownsRiskGroup(RiskGroupKey key) {
        if (!properties.getCoordination().isEnabled()) {
            return true;
        }
        return riskRepository.acquireScanLease(key, nodeId, properties.getCoordination().getLeaseDuration());
    }

    private void scanRiskGroup(RiskGroupKey key) {
        scanRiskGroup(key, null);
    }

    private void scanRiskGroup(RiskGroupKey key, PositionRiskTarget eventTarget) {
        if (!ownsRiskGroup(key)) {
            return;
        }
        transactionTemplate.executeWithoutResult(status -> {
            List<CalculatedPositionRisk> positions = riskRepository.calculatePositions(key,
                    properties.getCalculation().getMaxMarkAge());
            if (positions.isEmpty() && riskRepository.hasOpenPositions(key)) {
                return;
            }
            scanGroup(key, positions, eventTarget, Instant.now());
        });
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String settleAsset) {
        return riskRepository.latestAccount(userId, normalizeAsset(settleAsset))
                .orElseThrow(() -> new IllegalStateException("risk snapshot not found"));
    }

    public RiskPositionQueryResponse latestPositions(long userId) {
        List<RiskPositionSnapshotResponse> rows = riskRepository.latestPositions(userId);
        return new RiskPositionQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status, int limit) {
        LiquidationCandidateStatus candidateStatus = LiquidationCandidateStatus.valueOf(status.trim().toUpperCase());
        List<LiquidationCandidateResponse> rows = riskRepository.liquidationCandidates(candidateStatus, limit);
        return new LiquidationCandidateQueryResponse(rows.size(), rows);
    }

    private void scanGroup(RiskGroupKey key,
                           List<CalculatedPositionRisk> positions,
                           PositionRiskTarget eventTarget,
                           Instant now) {
        long walletBalance = riskRepository.walletBalanceUnits(key.userId(), key.settleAsset());
        List<CalculatedPositionRisk> crossPositions = positions.stream()
                .filter(position -> position.marginMode() == MarginMode.CROSS)
                .toList();
        long unrealizedPnl = sumUnrealizedPnl(crossPositions);
        long maintenanceMargin = sumMaintenanceMargin(crossPositions);
        long equity = RiskMath.equity(walletBalance, unrealizedPnl);
        long marginRatio = RiskMath.marginRatioPpm(maintenanceMargin, equity);
        RiskStatus accountStatus = RiskMath.status(marginRatio,
                properties.getCalculation().getWarningMarginRatioPpm(),
                properties.getCalculation().getLiquidationMarginRatioPpm());
        long snapshotId = sequenceRepository.nextSequence("risk-snapshot");
        RiskAccountSnapshotResponse account = new RiskAccountSnapshotResponse(snapshotId, key.userId(),
                key.settleAsset(), walletBalance, unrealizedPnl, equity, maintenanceMargin, marginRatio,
                accountStatus, now);
        riskRepository.saveAccountSnapshot(account);

        for (CalculatedPositionRisk position : positions) {
            long positionEquity = position.marginMode() == MarginMode.ISOLATED
                    ? RiskMath.equity(position.positionMarginUnits(), position.unrealizedPnlUnits())
                    : equity;
            long positionMarginRatio = RiskMath.marginRatioPpm(position.maintenanceMarginUnits(), positionEquity);
            RiskStatus positionStatus = RiskMath.status(positionMarginRatio,
                    properties.getCalculation().getWarningMarginRatioPpm(),
                    properties.getCalculation().getLiquidationMarginRatioPpm());
            riskRepository.savePositionSnapshot(snapshotId, position, positionMarginRatio, positionStatus, now);
            if ((position.marginMode() == MarginMode.CROSS && accountStatus == RiskStatus.LIQUIDATION)
                    || (position.marginMode() == MarginMode.ISOLATED && positionStatus == RiskStatus.LIQUIDATION)) {
                createCandidate(account, position, positionMarginRatio, positionEquity, now);
            }
        }
        if (eventTarget != null && positions.stream().noneMatch(position -> position.symbol().equals(eventTarget.symbol())
                && position.marginMode() == eventTarget.marginMode())) {
            CalculatedPositionRisk flatPosition = new CalculatedPositionRisk(eventTarget.userId(),
                    eventTarget.symbol(), eventTarget.marginMode(), eventTarget.instrumentVersion(), eventTarget.settleAsset(),
                    0L, 0L, 0L, 0L, 0L, 0L, 0L);
            riskRepository.savePositionSnapshot(snapshotId, flatPosition, 0L, RiskStatus.NORMAL, now);
        }
    }

    private void createCandidate(RiskAccountSnapshotResponse account,
                                 CalculatedPositionRisk position,
                                 long positionMarginRatio,
                                 long equityUnits,
                                 Instant now) {
        long candidateId = sequenceRepository.nextSequence("liquidation-candidate");
        long insertedId = riskRepository.createLiquidationCandidate(account, position, RiskStatus.LIQUIDATION,
                positionMarginRatio, equityUnits, candidateId, now);
        if (insertedId == 0L) {
            return;
        }
        LiquidationCandidateResponse candidate = riskRepository.liquidationCandidate(insertedId)
                .orElseThrow(() -> new IllegalStateException("liquidation candidate not found after insert "
                        + insertedId));
        LiquidationCandidateEvent event = new LiquidationCandidateEvent(
                candidate.candidateId(),
                candidate.snapshotId(),
                candidate.userId(),
                candidate.symbol(),
                candidate.marginMode(),
                candidate.instrumentVersion(),
                candidate.settleAsset(),
                candidate.signedQuantitySteps(),
                candidate.markPriceTicks(),
                candidate.equityUnits(),
                candidate.maintenanceMarginUnits(),
                candidate.marginRatioPpm(),
                candidate.eventTime());
        outboxRepository.enqueue(properties.getKafka().getLiquidationCandidatesTopic(), candidate.symbol(),
                "LIQUIDATION_CANDIDATE", payload(event), now);
    }

    private long sumUnrealizedPnl(List<CalculatedPositionRisk> positions) {
        long total = 0L;
        for (CalculatedPositionRisk position : positions) {
            total = Math.addExact(total, position.unrealizedPnlUnits());
        }
        return total;
    }

    private long sumMaintenanceMargin(List<CalculatedPositionRisk> positions) {
        long total = 0L;
        for (CalculatedPositionRisk position : positions) {
            total = Math.addExact(total, position.maintenanceMarginUnits());
        }
        return total;
    }

    private String normalizeAsset(String asset) {
        if (asset == null || asset.isBlank()) {
            throw new IllegalArgumentException("asset is required");
        }
        String normalized = asset.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9]{2,20}")) {
            throw new IllegalArgumentException("invalid asset: " + asset);
        }
        return normalized;
    }

    private String normalizeSymbol(String symbol) {
        if (symbol == null || symbol.isBlank()) {
            throw new IllegalArgumentException("symbol is required");
        }
        String normalized = symbol.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9-]{3,64}")) {
            throw new IllegalArgumentException("invalid symbol: " + symbol);
        }
        return normalized;
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize risk event", ex);
        }
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "risk-" + UUID.randomUUID();
    }
}
