package com.surprising.risk.provider.service;

import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.product.api.ProductLine;
import com.surprising.risk.api.model.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.repository.CoreRiskLiquidationProjectionRepository;
import com.surprising.risk.provider.repository.RiskRuleRepository;
import com.surprising.risk.provider.repository.RiskRuleRepository.RiskRuleOverride;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import org.springframework.stereotype.Service;

@Service
public class RiskService {

    private static final AdminCursorPage.SortSpec ASC =
            new AdminCursorPage.SortSpec("eventTime", "updated_at_epoch_ms", "liquidation_id", false);
    private static final AdminCursorPage.SortSpec DESC =
            new AdminCursorPage.SortSpec("eventTime", "updated_at_epoch_ms", "liquidation_id", true);

    private final RiskProperties properties;
    private final RiskAeronGateway aeron;
    private final CoreRiskLiquidationProjectionRepository liquidations;
    private final RiskRuleRepository rules;

    public RiskService(RiskProperties properties, RiskAeronGateway aeron,
                       CoreRiskLiquidationProjectionRepository liquidations, RiskRuleRepository rules) {
        this.properties = properties;
        this.aeron = aeron;
        this.liquidations = liquidations;
        this.rules = rules;
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String accountType, String settleAsset) {
        requireUserId(userId);
        String asset = normalizeAsset(settleAsset);
        String expectedAccountType = properties.getProductLine().accountTypeCode();
        if (accountType != null && !accountType.isBlank()
                && !expectedAccountType.equals(accountType.trim().toUpperCase(Locale.ROOT))) {
            throw new IllegalArgumentException("accountType does not match risk product line");
        }
        CoreUserStateView user = aeron.userState(userId);
        if (user == null) throw new IllegalStateException("risk snapshot not found");
        requireProductLine(user.productLine());
        List<CoreRiskSnapshotView> snapshots = riskState(userId).stream()
                .filter(value -> value.settleAsset().equals(asset))
                .toList();
        long wallet = snapshots.stream().mapToLong(CoreRiskSnapshotView::walletBalanceUnits).findFirst()
                .orElseGet(() -> user.balances().stream().filter(value -> value.asset().equals(asset))
                        .mapToLong(RiskService::total).findFirst().orElse(0L));
        List<CoreRiskSnapshotView> cross = snapshots.stream()
                .filter(value -> value.marginMode().name().equals(MarginMode.CROSS.name())).toList();
        long unrealized = sum(cross, CoreRiskSnapshotView::unrealizedPnlUnits);
        long maintenance = sum(cross, CoreRiskSnapshotView::maintenanceMarginUnits);
        long equity = Math.addExact(wallet, unrealized);
        long ratio = marginRatio(maintenance, equity);
        Instant now = Instant.now();
        return new RiskAccountSnapshotResponse(snapshotSequence(snapshots, user.revision()), userId,
                expectedAccountType, asset, wallet, unrealized, equity, maintenance, ratio,
                status(cross), now);
    }

    public RiskPositionQueryResponse latestPositions(long userId) {
        requireUserId(userId);
        Instant now = Instant.now();
        List<RiskPositionSnapshotResponse> rows = riskState(userId).stream()
                .map(value -> position(value, now)).toList();
        return new RiskPositionQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status, int limit) {
        return liquidationCandidates(status, limit, null, null);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(String status, int limit,
                                                                    String cursor, String sort) {
        LiquidationCandidateStatus requested = LiquidationCandidateStatus.valueOf(requireText(status, "status")
                .toUpperCase(Locale.ROOT));
        int normalizedLimit = bounded(limit, 1, 1000, "limit");
        AdminCursorPage.SortSpec sorting = AdminCursorPage.parseSort(sort, ASC, List.of(ASC, DESC));
        AdminCursorPage.Cursor decoded = AdminCursorPage.decodeCursor(cursor);
        List<LiquidationCandidateResponse> fetched = liquidations.find(requested, normalizedLimit + 1,
                decoded == null ? null : decoded.id(), sorting.descending());
        AdminCursorPage.CursorPage<LiquidationCandidateResponse> page = AdminCursorPage.page(fetched,
                normalizedLimit, sorting, LiquidationCandidateResponse::eventTime,
                LiquidationCandidateResponse::candidateId);
        List<LiquidationCandidateResponse> enriched = page.items().stream().map(this::enrich).toList();
        return new LiquidationCandidateQueryResponse(enriched.size(), enriched, page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public RiskRulesResponse riskRules() {
        List<RiskRuleOverride> overrides = rules.findAll();
        RiskRuleOverride scan = override(overrides, "RISK_SCAN_CONTROL");
        return new RiskRulesResponse(2, List.of(
                new RiskRuleResponse("GLOBAL_MARGIN_POLICY", "Core instrument risk policy",
                        "CORE_INSTRUMENT_RISK_POLICY", true, null, null, "core", null, null, null),
                rule("RISK_SCAN_CONTROL", "Aeron risk scan control", "SCAN_CONTROL",
                        scan == null ? properties.getCalculation().isEnabled() : scan.enabled(),
                        properties.getCalculation().getScanDelayMs(), properties.getCalculation().getScanBatchSize(), scan)));
    }

    public RiskRuleResponse updateRiskRule(String ruleCode, String adminUserId, RiskRuleUpdateCommand command) {
        String code = normalizeRuleCode(ruleCode);
        String admin = requireText(adminUserId, "adminUserId");
        if (command == null) throw new IllegalArgumentException("request is required");
        String reason = requireText(command.reason(), "reason");
        if (reason.length() > 500) throw new IllegalArgumentException("reason must be at most 500 characters");
        Instant now = Instant.now();
        RiskRuleOverride saved;
        if ("GLOBAL_MARGIN_POLICY".equals(code)) {
            throw new IllegalArgumentException(
                    "margin policy is owned by versioned Aeron Core instrument state");
        } else if ("RISK_SCAN_CONTROL".equals(code)) {
            boolean enabled = command.enabled() == null ? properties.getCalculation().isEnabled() : command.enabled();
            long delay = nonNegative(command.scanDelayMs() == null ? properties.getCalculation().getScanDelayMs()
                    : command.scanDelayMs(), "scanDelayMs");
            int batch = bounded(command.scanBatchSize() == null ? properties.getCalculation().getScanBatchSize()
                    : command.scanBatchSize(), 1, 10_000, "scanBatchSize");
            properties.getCalculation().setEnabled(enabled);
            properties.getCalculation().setScanDelayMs(delay);
            properties.getCalculation().setScanBatchSize(batch);
            saved = rules.upsert(code, ruleName(command.ruleName(), "Aeron risk scan control"), "SCAN_CONTROL",
                    enabled, delay, batch, admin, reason, now);
        } else {
            throw new IllegalArgumentException("unsupported risk rule: " + ruleCode);
        }
        return ruleFrom(saved);
    }

    private LiquidationCandidateResponse enrich(LiquidationCandidateResponse candidate) {
        CoreRiskSnapshotView risk = riskState(candidate.userId()).stream()
                .filter(value -> value.symbol().equals(candidate.symbol()))
                .filter(value -> value.positionSide().name().equals(candidate.positionSide().name()))
                .findFirst().orElse(null);
        if (risk == null) return candidate;
        return new LiquidationCandidateResponse(candidate.candidateId(), risk.priceSequence(), candidate.userId(),
                candidate.symbol(), MarginMode.valueOf(risk.marginMode().name()), candidate.positionSide(),
                risk.instrumentVersion(), candidate.accountType(), risk.settleAsset(), risk.signedQuantitySteps(),
                risk.markPriceTicks(), risk.equityUnits(), risk.maintenanceMarginUnits(), risk.marginRatioPpm(),
                candidate.status(), candidate.eventTime());
    }

    private List<CoreRiskSnapshotView> riskState(long userId) {
        return aeron.riskState(userId).stream()
                .filter(value -> value.userId() == userId)
                .sorted(Comparator.comparing(CoreRiskSnapshotView::symbol)
                        .thenComparing(value -> value.positionSide().name()))
                .toList();
    }

    private RiskPositionSnapshotResponse position(CoreRiskSnapshotView value, Instant eventTime) {
        return new RiskPositionSnapshotResponse(value.priceSequence(), value.userId(), value.symbol(),
                MarginMode.valueOf(value.marginMode().name()), PositionSide.valueOf(value.positionSide().name()),
                value.instrumentVersion(), value.settleAsset(), value.signedQuantitySteps(), value.entryPriceTicks(),
                value.markPriceTicks(), value.notionalUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.positionMarginUnits(), value.marginRatioPpm(),
                RiskStatus.valueOf(value.status()), eventTime);
    }

    private void requireProductLine(ProductLine actual) {
        if (actual != properties.getProductLine()) throw new IllegalStateException("Aeron user product line mismatch");
    }
    private static long total(CoreBalanceView value) { return Math.addExact(value.availableUnits(), value.lockedUnits()); }
    private static long snapshotSequence(List<CoreRiskSnapshotView> values, long fallback) {
        return values.stream().mapToLong(CoreRiskSnapshotView::priceSequence).max().orElse(fallback);
    }
    private static long sum(List<CoreRiskSnapshotView> values,
                            java.util.function.ToLongFunction<CoreRiskSnapshotView> extractor) {
        long total = 0;
        for (CoreRiskSnapshotView value : values) total = Math.addExact(total, extractor.applyAsLong(value));
        return total;
    }
    private static long marginRatio(long maintenance, long equity) {
        if (maintenance <= 0) return 0;
        if (equity <= 0) return Long.MAX_VALUE;
        if (maintenance > Long.MAX_VALUE / 1_000_000L) return Long.MAX_VALUE;
        return Math.multiplyExact(maintenance, 1_000_000L) / equity;
    }
    private static RiskStatus status(List<CoreRiskSnapshotView> snapshots) {
        if (snapshots.stream().anyMatch(value -> "LIQUIDATION".equals(value.status()))) {
            return RiskStatus.LIQUIDATION;
        }
        if (snapshots.stream().anyMatch(value -> "WARNING".equals(value.status()))) {
            return RiskStatus.WARNING;
        }
        return RiskStatus.NORMAL;
    }
    private static void requireUserId(long value) {
        if (value <= 0) throw new IllegalArgumentException("userId must be positive");
    }
    private static String normalizeAsset(String value) {
        String normalized = requireText(value, "settleAsset").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9]{2,20}")) throw new IllegalArgumentException("invalid settleAsset");
        return normalized;
    }
    private static String normalizeRuleCode(String value) {
        String normalized = requireText(value, "ruleCode").toUpperCase(Locale.ROOT);
        if (!normalized.matches("[A-Z0-9_.:-]{2,96}")) throw new IllegalArgumentException("invalid ruleCode");
        return normalized;
    }
    private static String ruleName(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.trim();
        if (normalized.length() > 120) throw new IllegalArgumentException("ruleName must be at most 120 characters");
        return normalized;
    }
    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
        return value.trim();
    }
    private static long nonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
    private static int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return value;
    }
    private static RiskRuleOverride override(List<RiskRuleOverride> values, String code) {
        return values.stream().filter(value -> value.ruleCode().equals(code)).findFirst().orElse(null);
    }
    private static RiskRuleResponse rule(String code, String name, String type, boolean enabled,
                                         Long delay, Integer batch,
                                         RiskRuleOverride override) {
        return new RiskRuleResponse(code, name, type, enabled, delay, batch,
                override == null ? "runtime" : "override", override == null ? null : override.adminUserId(),
                override == null ? null : override.reason(), override == null ? null : override.updatedAt());
    }
    private static RiskRuleResponse ruleFrom(RiskRuleOverride value) {
        return new RiskRuleResponse(value.ruleCode(), value.ruleName(), value.ruleType(), value.enabled(),
                value.scanDelayMs(),
                value.scanBatchSize(), "override", value.adminUserId(), value.reason(), value.updatedAt());
    }

    public record RiskRulesResponse(int ruleCount, List<RiskRuleResponse> rules) {}
    public record RiskRuleResponse(String ruleCode, String ruleName, String ruleType, boolean enabled,
                                   Long scanDelayMs,
                                   Integer scanBatchSize, String source, String adminUserId, String reason,
                                   Instant updatedAt) {}
    public record RiskRuleUpdateCommand(String ruleName, Boolean enabled, Long scanDelayMs, Integer scanBatchSize,
                                        String reason) {}
}
