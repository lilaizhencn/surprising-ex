package com.surprising.risk.provider.service;

import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateEvent;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskAccountUpdatedEvent;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionUpdatedEvent;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.model.CalculatedPositionRisk;
import com.surprising.risk.provider.model.PositionRiskTarget;
import com.surprising.risk.provider.model.RiskGroupKey;
import com.surprising.risk.provider.repository.RiskOutboxRepository;
import com.surprising.risk.provider.repository.RiskRepository;
import com.surprising.risk.provider.repository.RiskRepository.HighRiskAccount;
import com.surprising.risk.provider.repository.RiskRepository.RiskRuleOverride;
import com.surprising.risk.provider.repository.RiskSequenceRepository;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.kafka.core.KafkaTemplate;
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
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final String nodeId;

    @Autowired
    public RiskService(ObjectMapper objectMapper,
                       RiskProperties properties,
                       RiskRepository riskRepository,
                       RiskSequenceRepository sequenceRepository,
                       RiskOutboxRepository outboxRepository,
                       @Qualifier("riskKafkaTemplate") KafkaTemplate<String, String> kafkaTemplate,
                       PlatformTransactionManager transactionManager) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.riskRepository = riskRepository;
        this.sequenceRepository = sequenceRepository;
        this.outboxRepository = outboxRepository;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = new TransactionTemplate(transactionManager);
        this.nodeId = resolveNodeId(properties.getCoordination().getNodeId());
    }

    RiskService(ObjectMapper objectMapper,
                RiskProperties properties,
                RiskRepository riskRepository,
                RiskSequenceRepository sequenceRepository,
                RiskOutboxRepository outboxRepository,
                PlatformTransactionManager transactionManager) {
        this(objectMapper, properties, riskRepository, sequenceRepository, outboxRepository, null, transactionManager);
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
                    log.error("Failed to scan risk group userId={} accountType={} settleAsset={}: {}",
                            key.userId(), key.accountType(), key.settleAsset(), ex.getMessage(), ex);
                }
            }
        }
    }

    /**
     * Event-driven fast path used by account position events. The scheduled scanner is still the authoritative
     * fallback, but this method cuts liquidation latency after fills by scanning only the affected user/settle group.
     */
    public void scanPositionUpdate(long userId, String symbol, MarginMode marginMode, long instrumentVersion) {
        scanPositionUpdate(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, null);
    }

    public void scanPositionUpdate(long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   PositionSide positionSide,
                                   long instrumentVersion,
                                   String traceId) {
        if (!properties.getCalculation().isEnabled()) {
            return;
        }
        PositionRiskTarget target = riskRepository.riskTargetForPositionEvent(userId, normalizeSymbol(symbol),
                MarginMode.defaultIfNull(marginMode), PositionSide.defaultIfNull(positionSide),
                instrumentVersion).orElse(null);
        if (target == null) {
            log.debug("Position update did not resolve to a risk group userId={} symbol={} version={}",
                    userId, symbol, instrumentVersion);
            return;
        }
        scanRiskGroup(target.riskGroupKey(), target, traceId);
    }

    public void scanPositionUpdate(long userId,
                                   String symbol,
                                   MarginMode marginMode,
                                   long instrumentVersion,
                                   String traceId) {
        scanPositionUpdate(userId, symbol, marginMode, PositionSide.NET, instrumentVersion, traceId);
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
        scanRiskGroup(key, null, null);
    }

    private void scanRiskGroup(RiskGroupKey key, PositionRiskTarget eventTarget) {
        scanRiskGroup(key, eventTarget, null);
    }

    private void scanRiskGroup(RiskGroupKey key, PositionRiskTarget eventTarget, String traceId) {
        if (!ownsRiskGroup(key)) {
            return;
        }
        List<RealtimeRiskEvent> realtimeEvents = new ArrayList<>();
        transactionTemplate.executeWithoutResult(status -> {
            List<CalculatedPositionRisk> positions = riskRepository.calculatePositions(key,
                    properties.getCalculation().getMaxMarkAge());
            if (positions.isEmpty() && riskRepository.hasOpenPositions(key)) {
                return;
            }
            scanGroup(key, positions, eventTarget, Instant.now(), traceId, realtimeEvents);
        });
        publishRealtimeEvents(realtimeEvents);
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String settleAsset) {
        return latestAccount(userId, null, settleAsset);
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String accountType, String settleAsset) {
        return riskRepository.latestAccount(userId, scopedAccountType(accountType), normalizeAsset(settleAsset))
                .orElseThrow(() -> new IllegalStateException("risk snapshot not found"));
    }

    public RiskPositionQueryResponse latestPositions(long userId) {
        List<RiskPositionSnapshotResponse> rows = riskRepository.latestPositions(userId);
        return new RiskPositionQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status, int limit) {
        LiquidationCandidateStatus candidateStatus = LiquidationCandidateStatus.valueOf(status.trim().toUpperCase());
        List<LiquidationCandidateResponse> rows = riskRepository.liquidationCandidates(candidateStatus,
                normalizeLimit(limit));
        return new LiquidationCandidateQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status,
                                                                  int limit,
                                                                  String cursor,
                                                                  String sort) {
        LiquidationCandidateStatus candidateStatus = LiquidationCandidateStatus.valueOf(status.trim().toUpperCase());
        AdminCursorPage.CursorPage<LiquidationCandidateResponse> page = riskRepository.liquidationCandidatesPage(
                candidateStatus, normalizeLimit(limit), cursor, sort);
        return new LiquidationCandidateQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public RiskRulesResponse riskRules() {
        List<RiskRuleOverride> overrides = riskRepository.riskRuleOverrides();
        RiskRuleOverride marginOverride = override(overrides, "GLOBAL_MARGIN_POLICY");
        RiskRuleOverride scanOverride = override(overrides, "RISK_SCAN_CONTROL");
        List<RiskRuleResponse> rules = List.of(
                rule("GLOBAL_MARGIN_POLICY", "Global margin thresholds", "GLOBAL_MARGIN",
                        marginOverride == null ? null : marginOverride.enabled(),
                        properties.getCalculation().getWarningMarginRatioPpm(),
                        properties.getCalculation().getLiquidationMarginRatioPpm(),
                        null,
                        null,
                        marginOverride),
                rule("RISK_SCAN_CONTROL", "Risk scan control", "SCAN_CONTROL",
                        scanOverride == null ? properties.getCalculation().isEnabled() : scanOverride.enabled(),
                        null,
                        null,
                        properties.getCalculation().getScanDelayMs(),
                        properties.getCalculation().getScanBatchSize(),
                        scanOverride));
        return new RiskRulesResponse(rules.size(), rules);
    }

    public RiskRuleResponse updateRiskRule(String ruleCode, String adminUserId, RiskRuleUpdateCommand command) {
        String normalizedCode = normalizeRuleCode(ruleCode);
        String normalizedAdmin = requireText(adminUserId, "adminUserId");
        if (command == null) {
            throw new IllegalArgumentException("request is required");
        }
        String reason = requireText(command.reason(), "reason");
        if (reason.length() > 500) {
            throw new IllegalArgumentException("reason must be at most 500 characters");
        }
        if ("GLOBAL_MARGIN_POLICY".equals(normalizedCode)) {
            long warning = nonNegative(
                    command.warningMarginRatioPpm() == null
                            ? properties.getCalculation().getWarningMarginRatioPpm()
                            : command.warningMarginRatioPpm(),
                    "warningMarginRatioPpm");
            long liquidation = nonNegative(
                    command.liquidationMarginRatioPpm() == null
                            ? properties.getCalculation().getLiquidationMarginRatioPpm()
                            : command.liquidationMarginRatioPpm(),
                    "liquidationMarginRatioPpm");
            if (warning >= liquidation) {
                throw new IllegalArgumentException("warningMarginRatioPpm must be less than liquidationMarginRatioPpm");
            }
            properties.getCalculation().setWarningMarginRatioPpm(warning);
            properties.getCalculation().setLiquidationMarginRatioPpm(liquidation);
            RiskRuleOverride override = riskRepository.upsertRiskRuleOverride(normalizedCode,
                    ruleName(command.ruleName(), "Global margin thresholds"), "GLOBAL_MARGIN",
                    command.enabled() == null || command.enabled(), warning, liquidation, null, null,
                    normalizedAdmin, reason, Instant.now());
            return ruleFromOverride(override);
        }
        if ("RISK_SCAN_CONTROL".equals(normalizedCode)) {
            boolean enabled = command.enabled() == null ? properties.getCalculation().isEnabled() : command.enabled();
            long scanDelayMs = nonNegative(command.scanDelayMs() == null
                    ? properties.getCalculation().getScanDelayMs()
                    : command.scanDelayMs(), "scanDelayMs");
            int scanBatchSize = bounded(command.scanBatchSize() == null
                    ? properties.getCalculation().getScanBatchSize()
                    : command.scanBatchSize(), 1, 10_000, "scanBatchSize");
            properties.getCalculation().setEnabled(enabled);
            properties.getCalculation().setScanDelayMs(scanDelayMs);
            properties.getCalculation().setScanBatchSize(scanBatchSize);
            RiskRuleOverride override = riskRepository.upsertRiskRuleOverride(normalizedCode,
                    ruleName(command.ruleName(), "Risk scan control"), "SCAN_CONTROL", enabled,
                    null, null, scanDelayMs, scanBatchSize, normalizedAdmin, reason, Instant.now());
            return ruleFromOverride(override);
        }
        throw new IllegalArgumentException("unsupported risk rule: " + ruleCode);
    }

    public HighRiskAccountsResponse highRiskAccounts(Long minMarginRatioPpm, int limit) {
        long threshold = nonNegative(minMarginRatioPpm == null
                ? properties.getCalculation().getWarningMarginRatioPpm()
                : minMarginRatioPpm, "minMarginRatioPpm");
        List<HighRiskAccount> rows = riskRepository.highRiskAccounts(threshold, normalizeLimit(limit));
        List<HighRiskAccountResponse> accounts = rows.stream()
                .map(row -> new HighRiskAccountResponse(row.snapshotId(), row.userId(), row.accountType(),
                        row.settleAsset(), row.walletBalanceUnits(), row.unrealizedPnlUnits(), row.equityUnits(),
                        row.maintenanceMarginUnits(), row.marginRatioPpm(), row.status(), row.eventTime(),
                        row.positionCount(), row.riskPositionCount(), row.activeCandidateCount(),
                        row.topSymbol(), row.topMarginMode(), row.topPositionMarginRatioPpm(),
                        row.topPositionStatus(), row.riskLevel()))
                .toList();
        long liquidationCount = accounts.stream()
                .filter(account -> "LIQUIDATION".equals(account.riskLevel()))
                .count();
        long warningCount = accounts.stream()
                .filter(account -> "WARNING".equals(account.riskLevel()))
                .count();
        return new HighRiskAccountsResponse(threshold, accounts.size(), liquidationCount, warningCount, accounts);
    }

    public HighRiskAccountsResponse highRiskAccounts(Long minMarginRatioPpm, int limit, String cursor, String sort) {
        long threshold = nonNegative(minMarginRatioPpm == null
                ? properties.getCalculation().getWarningMarginRatioPpm()
                : minMarginRatioPpm, "minMarginRatioPpm");
        AdminCursorPage.CursorPage<HighRiskAccount> page = riskRepository.highRiskAccountsPage(threshold,
                normalizeLimit(limit), cursor, sort);
        List<HighRiskAccountResponse> accounts = page.items().stream()
                .map(row -> new HighRiskAccountResponse(row.snapshotId(), row.userId(), row.accountType(),
                        row.settleAsset(), row.walletBalanceUnits(), row.unrealizedPnlUnits(), row.equityUnits(),
                        row.maintenanceMarginUnits(), row.marginRatioPpm(), row.status(), row.eventTime(),
                        row.positionCount(), row.riskPositionCount(), row.activeCandidateCount(),
                        row.topSymbol(), row.topMarginMode(), row.topPositionMarginRatioPpm(),
                        row.topPositionStatus(), row.riskLevel()))
                .toList();
        long liquidationCount = accounts.stream()
                .filter(account -> "LIQUIDATION".equals(account.riskLevel()))
                .count();
        long warningCount = accounts.stream()
                .filter(account -> "WARNING".equals(account.riskLevel()))
                .count();
        return new HighRiskAccountsResponse(threshold, accounts.size(), liquidationCount, warningCount, accounts,
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private void scanGroup(RiskGroupKey key,
                           List<CalculatedPositionRisk> positions,
                           PositionRiskTarget eventTarget,
                           Instant now,
                           String traceId,
                           List<RealtimeRiskEvent> realtimeEvents) {
        long walletBalance = riskRepository.walletBalanceUnits(key.userId(), key.accountType(), key.settleAsset());
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
                key.accountType(), key.settleAsset(), walletBalance, unrealizedPnl, equity, maintenanceMargin, marginRatio,
                accountStatus, now);
        riskRepository.saveAccountSnapshot(account);
        stageAccountRisk(account, traceId, realtimeEvents);

        for (CalculatedPositionRisk position : positions) {
            long positionEquity = position.marginMode() == MarginMode.ISOLATED
                    ? RiskMath.equity(position.positionMarginUnits(), position.unrealizedPnlUnits())
                    : equity;
            long positionMarginRatio = RiskMath.marginRatioPpm(position.maintenanceMarginUnits(), positionEquity);
            RiskStatus positionStatus = RiskMath.status(positionMarginRatio,
                    properties.getCalculation().getWarningMarginRatioPpm(),
                    properties.getCalculation().getLiquidationMarginRatioPpm());
            riskRepository.savePositionSnapshot(snapshotId, position, positionMarginRatio, positionStatus, now);
            stagePositionRisk(snapshotId, position, positionMarginRatio, positionStatus, now, traceId, realtimeEvents);
            if ((position.marginMode() == MarginMode.CROSS && accountStatus == RiskStatus.LIQUIDATION)
                    || (position.marginMode() == MarginMode.ISOLATED && positionStatus == RiskStatus.LIQUIDATION)) {
                createCandidate(account, position, positionMarginRatio, positionEquity, now);
            }
        }
        if (eventTarget != null && positions.stream().noneMatch(position -> position.symbol().equals(eventTarget.symbol())
                && position.marginMode() == eventTarget.marginMode()
                && position.positionSide() == eventTarget.positionSide())) {
            CalculatedPositionRisk flatPosition = new CalculatedPositionRisk(eventTarget.userId(),
                    eventTarget.symbol(), eventTarget.marginMode(), eventTarget.positionSide(),
                    eventTarget.instrumentVersion(), eventTarget.settleAsset(), 0L, 0L, 0L, 0L, 0L, 0L, 0L);
            riskRepository.savePositionSnapshot(snapshotId, flatPosition, 0L, RiskStatus.NORMAL, now);
            stagePositionRisk(snapshotId, flatPosition, 0L, RiskStatus.NORMAL, now, traceId, realtimeEvents);
        }
    }

    private void stageAccountRisk(RiskAccountSnapshotResponse account,
                                  String traceId,
                                  List<RealtimeRiskEvent> realtimeEvents) {
        RiskAccountUpdatedEvent event = RiskAccountUpdatedEvent.from(sequenceRepository.nextSequence("risk-event"),
                account, traceId);
        realtimeEvents.add(new RealtimeRiskEvent(properties.getKafka().getAccountRiskEventsTopic(),
                account.userId() + ":" + account.accountType() + ":" + account.settleAsset(),
                "RISK_ACCOUNT_UPDATED", payload(event)));
    }

    private void stagePositionRisk(long snapshotId,
                                   CalculatedPositionRisk position,
                                   long marginRatioPpm,
                                   RiskStatus status,
                                   Instant now,
                                   String traceId,
                                   List<RealtimeRiskEvent> realtimeEvents) {
        RiskPositionUpdatedEvent event = new RiskPositionUpdatedEvent(
                sequenceRepository.nextSequence("risk-event"),
                properties.getKafka().getProductLine(),
                snapshotId,
                position.userId(),
                position.symbol(),
                position.marginMode(),
                position.positionSide(),
                position.instrumentVersion(),
                position.settleAsset(),
                position.signedQuantitySteps(),
                position.entryPriceTicks(),
                position.markPriceTicks(),
                position.notionalUnits(),
                position.unrealizedPnlUnits(),
                position.maintenanceMarginUnits(),
                position.positionMarginUnits(),
                marginRatioPpm,
                status,
                now,
                traceId);
        realtimeEvents.add(new RealtimeRiskEvent(properties.getKafka().getPositionRiskEventsTopic(), position.symbol(),
                "RISK_POSITION_UPDATED", payload(event)));
    }

    private void publishRealtimeEvents(List<RealtimeRiskEvent> events) {
        if (kafkaTemplate == null) {
            return;
        }
        for (RealtimeRiskEvent event : events) {
            kafkaTemplate.send(event.topic(), event.eventKey(), event.payload()).whenComplete((result, ex) -> {
                if (ex != null) {
                    log.warn("Failed to publish committed risk snapshot topic={} key={} type={}: {}",
                            event.topic(), event.eventKey(), event.eventType(), ex.getMessage());
                }
            });
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
                candidate.positionSide(),
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

    private String normalizeAccountType(String accountType) {
        if (accountType == null || accountType.isBlank()) {
            return "USDT_PERPETUAL";
        }
        String normalized = accountType.trim().toUpperCase();
        if (!normalized.matches("[A-Z0-9_]{2,32}")) {
            throw new IllegalArgumentException("invalid accountType: " + accountType);
        }
        return normalized;
    }

    private String scopedAccountType(String accountType) {
        ProductLine productLine = currentProductLineFilter();
        if (productLine == null) {
            return normalizeAccountType(accountType);
        }
        String currentAccountType = productLine.accountTypeCode();
        if (accountType == null || accountType.isBlank()) {
            return currentAccountType;
        }
        String normalized = normalizeAccountType(accountType);
        if (!currentAccountType.equals(normalized)) {
            throw new IllegalArgumentException("accountType must match current product line account");
        }
        return normalized;
    }

    private ProductLine currentProductLineFilter() {
        RiskProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        return kafka != null && kafka.isProductTopicsEnabled() ? kafka.getProductLine() : null;
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

    private RiskRuleOverride override(List<RiskRuleOverride> overrides, String ruleCode) {
        return overrides.stream()
                .filter(item -> ruleCode.equals(item.ruleCode()))
                .findFirst()
                .orElse(null);
    }

    private RiskRuleResponse rule(String ruleCode,
                                  String ruleName,
                                  String ruleType,
                                  Boolean enabled,
                                  Long warningMarginRatioPpm,
                                  Long liquidationMarginRatioPpm,
                                  Long scanDelayMs,
                                  Integer scanBatchSize,
                                  RiskRuleOverride override) {
        return new RiskRuleResponse(ruleCode, ruleName, ruleType, enabled == null || enabled,
                warningMarginRatioPpm, liquidationMarginRatioPpm, scanDelayMs, scanBatchSize,
                override == null ? "runtime" : "override",
                override == null ? null : override.adminUserId(),
                override == null ? null : override.reason(),
                override == null ? null : override.updatedAt());
    }

    private RiskRuleResponse ruleFromOverride(RiskRuleOverride override) {
        return new RiskRuleResponse(override.ruleCode(), override.ruleName(), override.ruleType(),
                override.enabled(), override.warningMarginRatioPpm(), override.liquidationMarginRatioPpm(),
                override.scanDelayMs(), override.scanBatchSize(), "override", override.adminUserId(),
                override.reason(), override.updatedAt());
    }

    private String normalizeRuleCode(String value) {
        String normalized = requireText(value, "ruleCode").toUpperCase();
        if (!normalized.matches("[A-Z0-9_.:-]{2,96}")) {
            throw new IllegalArgumentException("invalid ruleCode: " + value);
        }
        return normalized;
    }

    private String ruleName(String value, String fallback) {
        if (value == null || value.isBlank()) {
            return fallback;
        }
        String normalized = value.trim();
        if (normalized.length() > 120) {
            throw new IllegalArgumentException("ruleName must be at most 120 characters");
        }
        return normalized;
    }

    private String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value.trim();
    }

    private long nonNegative(long value, String field) {
        if (value < 0) {
            throw new IllegalArgumentException(field + " must be non-negative");
        }
        return value;
    }

    private int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) {
            throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        }
        return value;
    }

    private int normalizeLimit(int limit) {
        return bounded(limit, 1, 1000, "limit");
    }

    private String payload(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JacksonException ex) {
            throw new IllegalStateException("failed to serialize risk event", ex);
        }
    }

    private record RealtimeRiskEvent(String topic, String eventKey, String eventType, String payload) {
    }

    private String resolveNodeId(String configured) {
        if (configured != null && !configured.isBlank()) {
            return configured.trim();
        }
        return "risk-" + UUID.randomUUID();
    }

    public record RiskRulesResponse(int ruleCount,
                                    List<RiskRuleResponse> rules) {
    }

    public record RiskRuleResponse(String ruleCode,
                                   String ruleName,
                                   String ruleType,
                                   boolean enabled,
                                   Long warningMarginRatioPpm,
                                   Long liquidationMarginRatioPpm,
                                   Long scanDelayMs,
                                   Integer scanBatchSize,
                                   String source,
                                   String adminUserId,
                                   String reason,
                                   Instant updatedAt) {
    }

    public record RiskRuleUpdateCommand(String ruleName,
                                        Boolean enabled,
                                        Long warningMarginRatioPpm,
                                        Long liquidationMarginRatioPpm,
                                        Long scanDelayMs,
                                        Integer scanBatchSize,
                                        String reason) {
    }

    public record HighRiskAccountsResponse(long minMarginRatioPpm,
                                           int accountCount,
                                           long liquidationCount,
                                           long warningCount,
                                           List<HighRiskAccountResponse> accounts,
                                           String nextCursor,
                                           boolean hasMore,
                                           String sort,
                                           int limit) {

        public HighRiskAccountsResponse(long minMarginRatioPpm,
                                        int accountCount,
                                        long liquidationCount,
                                        long warningCount,
                                        List<HighRiskAccountResponse> accounts) {
            this(minMarginRatioPpm, accountCount, liquidationCount, warningCount, accounts,
                    null, false, "eventTime.desc", accountCount);
        }
    }

    public record HighRiskAccountResponse(long snapshotId,
                                          long userId,
                                          String accountType,
                                          String settleAsset,
                                          long walletBalanceUnits,
                                          long unrealizedPnlUnits,
                                          long equityUnits,
                                          long maintenanceMarginUnits,
                                          long marginRatioPpm,
                                          RiskStatus status,
                                          Instant eventTime,
                                          int positionCount,
                                          int riskPositionCount,
                                          int activeCandidateCount,
                                          String topSymbol,
                                          MarginMode topMarginMode,
                                          Long topPositionMarginRatioPpm,
                                          RiskStatus topPositionStatus,
                                          String riskLevel) {
    }
}
