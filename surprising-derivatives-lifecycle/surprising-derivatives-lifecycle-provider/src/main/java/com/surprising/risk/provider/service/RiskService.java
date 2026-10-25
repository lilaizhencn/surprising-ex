package com.surprising.risk.provider.service;

import com.surprising.aeron.protocol.CoreBalanceView;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.CoreUserStateView;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.product.api.ProductLine;
import com.surprising.trading.api.utils.AdminCursorPage;
import com.surprising.risk.api.model.LiquidationCandidateQueryResponse;
import com.surprising.risk.api.model.LiquidationCandidateResponse;
import com.surprising.risk.api.model.LiquidationCandidateStatus;
import com.surprising.risk.api.model.RiskAccountSnapshotResponse;
import com.surprising.risk.api.model.RiskPositionQueryResponse;
import com.surprising.risk.api.model.RiskPositionSnapshotResponse;
import com.surprising.risk.api.model.RiskStatus;
import com.surprising.risk.provider.config.RiskProperties;
import com.surprising.risk.provider.repository.CoreRiskLiquidationProjectionRepository;
import com.surprising.trading.api.model.MarginMode;
import com.surprising.trading.api.model.PositionSide;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import com.surprising.risk.provider.model.RiskRuleModels.*;
import com.surprising.risk.provider.model.LiquidationCandidatePageQuery;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Qualifier;

@Service
public class RiskService {

    private static final AdminCursorPage.SortSpec ASC =
            new AdminCursorPage.SortSpec("eventTime", "updated_at_epoch_ms", "liquidation_id", false);
    private static final AdminCursorPage.SortSpec DESC =
            new AdminCursorPage.SortSpec("eventTime", "updated_at_epoch_ms", "liquidation_id", true);

    private final RiskProperties properties;
    private final RiskAeronGateway aeron;
    private final CoreRiskLiquidationProjectionRepository liquidations;
    private final LiquidationPriceCalculator liquidationPrices;

    public RiskService(RiskProperties properties, RiskAeronGateway aeron,
                       CoreRiskLiquidationProjectionRepository liquidations,
                       @Qualifier("derivativesInstrumentSnapshotCache") InstrumentSnapshotCache instruments) {
        this.properties = properties;
        this.aeron = aeron;
        this.liquidations = liquidations;
        this.liquidationPrices = new LiquidationPriceCalculator(instruments, properties.getProductLine());
    }

    public RiskAccountSnapshotResponse latestAccount(long userId, String accountType, String settleAsset) {
        String asset = settleAsset;
        String expectedAccountType = properties.getProductLine().accountTypeCode();
        if (accountType != null && !expectedAccountType.equals(accountType)) {
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
        // Core values cross equity from the same current prices, including option premium value.
        long equity = cross.isEmpty() ? wallet : cross.getFirst().equityUnits();
        long ratio = marginRatio(maintenance, equity);
        Instant now = Instant.now();
        return new RiskAccountSnapshotResponse(snapshotSequence(snapshots, user.revision()), userId,
                expectedAccountType, asset, wallet, unrealized, equity, maintenance, ratio,
                status(cross), now);
    }

    public RiskPositionQueryResponse latestPositions(long userId) {
        Instant now = Instant.now();
        List<CoreRiskSnapshotView> snapshots = riskState(userId);
        List<RiskPositionSnapshotResponse> rows = snapshots.stream()
                .map(value -> position(value, snapshots, now)).toList();
        return new RiskPositionQueryResponse(rows.size(), rows);
    }

    public LiquidationCandidateQueryResponse liquidationCandidates(LiquidationCandidatePageQuery query) {
        AdminCursorPage.SortSpec sorting = query.descending() ? DESC : ASC;
        List<LiquidationCandidateResponse> fetched = liquidations.find(query.status(), query.limit() + 1,
                query.afterId(), query.descending());
        AdminCursorPage.CursorPage<LiquidationCandidateResponse> page = AdminCursorPage.page(fetched,
                query.limit(), sorting, LiquidationCandidateResponse::eventTime,
                LiquidationCandidateResponse::candidateId);
        List<LiquidationCandidateResponse> enriched = page.items().stream().map(this::enrich).toList();
        return new LiquidationCandidateQueryResponse(enriched.size(), enriched, page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public RiskRulesResponse riskRules() {
        CoreRiskScanControlView scan = aeron.riskScanControl();
        return new RiskRulesResponse(2, List.of(
                new RiskRuleResponse("GLOBAL_MARGIN_POLICY", "Core instrument risk policy",
                        "CORE_INSTRUMENT_RISK_POLICY", null, true, null, null, "core", null, null, null),
                ruleFrom(scan)));
    }

    public RiskRuleResponse updateRiskRule(String ruleCode, String adminUserId, RiskRuleUpdateCommand command) {
        String code = ruleCode;
        String admin = adminUserId;
        String reason = command.reason();
        if ("GLOBAL_MARGIN_POLICY".equals(code)) {
            throw new IllegalArgumentException(
                    "margin policy is owned by versioned Aeron Core instrument state");
        } else if ("RISK_SCAN_CONTROL".equals(code)) {
            if (command.expectedVersion() == null || command.expectedVersion() <= 0) {
                throw new IllegalArgumentException("expectedVersion must be positive");
            }
            CoreRiskScanControlView current = aeron.riskScanControl();
            var update = new UpdateRiskScanControlCommand(command.expectedVersion(),
                    command.ruleName() == null ? current.ruleName() : command.ruleName(),
                    command.enabled() == null ? current.enabled() : command.enabled(),
                    nonNegative(command.scanDelayMs() == null ? current.scanDelayMs() : command.scanDelayMs(),
                            "scanDelayMs"),
                    bounded(command.scanBatchSize() == null ? current.scanBatchSize() : command.scanBatchSize(),
                            1, 4_096, "scanBatchSize"),
                    admin, reason);
            return ruleFrom(aeron.updateRiskScanControl(update));
        } else {
            throw new IllegalArgumentException("unsupported risk rule: " + ruleCode);
        }
    }

    private LiquidationCandidateResponse enrich(LiquidationCandidateResponse candidate) {
        CoreRiskSnapshotView risk = riskState(candidate.userId()).stream()
                .filter(value -> value.instrumentId().equals(candidate.instrumentId()))
                .filter(value -> value.positionSide().name().equals(candidate.positionSide().name()))
                .filter(value -> value.marginMode().name().equals(candidate.marginMode().name()))
                .findFirst().orElse(null);
        if (risk == null) return candidate;
        return new LiquidationCandidateResponse(candidate.candidateId(), risk.priceSequence(), candidate.userId(),
                candidate.instrumentId(), candidate.marginMode(), candidate.positionSide(),
                candidate.accountType(), candidate.settleAsset(), candidate.signedQuantitySteps(),
                risk.markPriceTicks(), risk.equityUnits(), risk.maintenanceMarginUnits(), risk.marginRatioPpm(),
                candidate.status(), candidate.eventTime());
    }

    private List<CoreRiskSnapshotView> riskState(long userId) {
        return aeron.riskState(userId).stream()
                .filter(value -> value.userId() == userId)
                .sorted(Comparator.comparing(CoreRiskSnapshotView::instrumentId)
                        .thenComparing(value -> value.positionSide().name()))
                .toList();
    }

    private RiskPositionSnapshotResponse position(CoreRiskSnapshotView value,
                                                  List<CoreRiskSnapshotView> accountPositions, Instant eventTime) {
        return new RiskPositionSnapshotResponse(value.priceSequence(), value.userId(), value.instrumentId(),
                MarginMode.valueOf(value.marginMode().name()), PositionSide.valueOf(value.positionSide().name()),
                value.settleAsset(), value.signedQuantitySteps(), value.entryPriceTicks(),
                value.markPriceTicks(), value.notionalUnits(), value.unrealizedPnlUnits(),
                value.maintenanceMarginUnits(), value.positionMarginUnits(), value.marginRatioPpm(),
                RiskStatus.valueOf(value.status()), eventTime,
                liquidationPrices.price(value, accountPositions));
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





    private static long nonNegative(long value, String field) {
        if (value < 0) throw new IllegalArgumentException(field + " must be non-negative");
        return value;
    }
    private static int bounded(int value, int min, int max, String field) {
        if (value < min || value > max) throw new IllegalArgumentException(field + " must be between " + min + " and " + max);
        return value;
    }
    private static RiskRuleResponse ruleFrom(CoreRiskScanControlView value) {
        return new RiskRuleResponse("RISK_SCAN_CONTROL", value.ruleName(), "SCAN_CONTROL", value.version(),
                value.enabled(), value.scanDelayMs(), value.scanBatchSize(), "AERON_CORE",
                value.updatedBy(), value.reason(),
                value.updatedAtEpochMillis() == 0 ? null : Instant.ofEpochMilli(value.updatedAtEpochMillis()));
    }

}
