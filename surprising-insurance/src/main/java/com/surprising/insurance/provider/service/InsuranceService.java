package com.surprising.insurance.provider.service;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.InsuranceLedgerReference;
import com.surprising.insurance.provider.repository.CoreInsuranceProjectionRepository;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
    public class InsuranceService {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final InsuranceProperties properties;
    private final InsuranceSequenceRepository sequenceRepository;
    private final InsuranceFundLedgerRepository ledgerRepository;
    private final InsuranceCoverageRepository coverageRepository;
    private final CoreInsuranceProjectionRepository projectionRepository;
    private final InsuranceAeronGateway aeron;

    public InsuranceService(InsuranceProperties properties,
                            InsuranceSequenceRepository sequenceRepository,
                            InsuranceFundLedgerRepository ledgerRepository,
                            InsuranceCoverageRepository coverageRepository,
                            CoreInsuranceProjectionRepository projectionRepository,
                            InsuranceAeronGateway aeron) {
        this.properties = properties;
        this.sequenceRepository = sequenceRepository;
        this.ledgerRepository = ledgerRepository;
        this.coverageRepository = coverageRepository;
        this.projectionRepository = projectionRepository;
        this.aeron = aeron;
    }

    @Transactional
    public synchronized CoverageCycle coverDeficits() {
        if (!properties.getCoverage().isEnabled()) {
            return CoverageCycle.disabled();
        }
        int batchSize = properties.getCoverage().getBatchSize();
        CoreLiquidationWorkView work = aeron.resolutionWork(CoreLiquidationWorkView.Purpose.INSURANCE,
                0, batchSize, 1_048_576);
        requireOwnedWork(work);
        int covered = 0;
        for (CoreLiquidationWorkView.Resolution resolution : work.resolutions()) {
            if (coverDeficit(new com.surprising.insurance.provider.model.CoreLiquidationProjection(
                    resolution.liquidationId(), resolution.userId(), resolution.asset(),
                    resolution.deficitUnits()))) {
                covered++;
            }
        }
        return new CoverageCycle(true, work.resolutions().size(), covered,
                work.resolutions().size() - covered);
    }

    private void requireOwnedWork(CoreLiquidationWorkView work) {
        if (work.productLine() != properties.getKafka().getProductLine()) {
            throw new IllegalStateException("Core insurance work ProductLine mismatch");
        }
        if (!work.actions().isEmpty() || work.resolutions().stream()
                .anyMatch(value -> value.purpose() != CoreLiquidationWorkView.Purpose.INSURANCE)) {
            throw new IllegalStateException("Core insurance authority mismatch");
        }
    }

    @Transactional
    public InsuranceFundBalanceResponse adjustFund(InsuranceFundAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        String asset = normalizeAsset(request.asset());
        String referenceId = normalizeReferenceId(request.referenceId());
        String accountType = accountType();
        Instant now = Instant.now();
        long currentBalance = aeron.balance(asset);
        long nextBalance = Math.addExact(currentBalance, request.amountUnits());
        if (nextBalance < 0) throw new IllegalArgumentException("insufficient insurance fund balance");
        UUID commandId = stableId("INSURANCE_FUND:" + properties.getKafka().getProductLine() + ':' + referenceId);
        aeron.command(CoreMessageType.ADJUST_INSURANCE_FUND, commandId,
                TradingCommandCodec.encodeAdjustInsuranceFund(
                        new AdjustInsuranceFundCommand(asset, request.amountUnits())));
        long committedBalance = aeron.balance(asset);
        boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"),
                accountType, asset, request.amountUnits(), committedBalance,
                "FUND_ADJUSTMENT", referenceId, request.reason(), now);
        if (!inserted) {
            requireReferenceMatches("FUND_ADJUSTMENT", referenceId, accountType, asset,
                    request.amountUnits(), request.reason());
            return new InsuranceFundBalanceResponse(asset, committedBalance, now);
        }
        return new InsuranceFundBalanceResponse(asset, committedBalance, now);
    }

    @Transactional
    public void collectLiquidationFee(LiquidationFeeSettledEvent event) {
        if (event.amountUnits() <= 0) {
            throw new IllegalArgumentException("liquidation fee amountUnits must be positive");
        }
        String accountType = normalizeAccountType(event.accountType());
        requireProviderAccountType(accountType);
        Instant now = event.eventTime() == null ? Instant.now() : event.eventTime();
        String referenceId = event.tradeId() + ":" + event.orderId();
        String asset = normalizeAsset(event.asset());
        UUID commandId = stableId("LIQUIDATION_FEE:" + properties.getKafka().getProductLine() + ':' + referenceId);
        aeron.command(CoreMessageType.ADJUST_INSURANCE_FUND, commandId,
                TradingCommandCodec.encodeAdjustInsuranceFund(
                        new AdjustInsuranceFundCommand(asset, event.amountUnits())));
        long nextBalance = aeron.balance(asset);
        boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"),
                accountType, asset, event.amountUnits(), nextBalance,
                "LIQUIDATION_FEE", referenceId, "COLLECT_LIQUIDATION_FEE", now);
        if (!inserted) {
            requireReferenceMatches("LIQUIDATION_FEE", referenceId, accountType, asset,
                    event.amountUnits(), "COLLECT_LIQUIDATION_FEE");
            return;
        }
    }

    public InsuranceFundBalanceQueryResponse balances(String asset) {
        String normalized = asset == null || asset.isBlank() ? null : normalizeAsset(asset);
        var rows = CoreStateQueryCodec.decodeTreasuryState(aeron.treasury()).stream()
                .filter(value -> normalized == null || value.asset().equals(normalized))
                .map(value -> new InsuranceFundBalanceResponse(value.asset(), value.insuranceBalanceUnits(),
                        Instant.now())).toList();
        return new InsuranceFundBalanceQueryResponse(rows.size(), rows);
    }

    public InsuranceLedgerQueryResponse ledger(String asset, int limit) {
        return ledger(asset, limit, null, null);
    }

    public InsuranceLedgerQueryResponse ledger(String asset, int limit, String cursor, String sort) {
        int capped = normalizeLimit(limit);
        var page = ledgerRepository.page(accountType(),
                asset == null || asset.isBlank() ? null : normalizeAsset(asset), capped, cursor, sort);
        return new InsuranceLedgerQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    public InsuranceCoverageQueryResponse coverages(Long userId, String asset, int limit) {
        return coverages(userId, asset, limit, null, null);
    }

    public InsuranceCoverageQueryResponse coverages(Long userId,
                                                    String asset,
                                                    int limit,
                                                    String cursor,
                                                    String sort) {
        if (userId != null && userId <= 0) {
            throw new IllegalArgumentException("userId must be positive");
        }
        int capped = normalizeLimit(limit);
        var page = coverageRepository.page(accountType(), userId,
                asset == null || asset.isBlank() ? null : normalizeAsset(asset), capped, cursor, sort);
        return new InsuranceCoverageQueryResponse(page.items().size(), page.items(),
                page.nextCursor(), page.hasMore(), page.sort(), page.limit());
    }

    private boolean coverDeficit(com.surprising.insurance.provider.model.CoreLiquidationProjection deficit) {
        Instant now = Instant.now();
        long availableFund = aeron.balance(deficit.asset());
        long coverUnits = InsuranceMath.coverAmount(deficit.deficitUnits(), availableFund);
        if (coverUnits <= 0) {
            return false;
        }
        long coverageId = sequenceRepository.next("insurance-coverage");
        long remainingDeficit = Math.subtractExact(deficit.deficitUnits(), coverUnits);
        UUID commandId = stableId("INSURANCE_COVER:" + properties.getKafka().getProductLine() + ':'
                + deficit.liquidationId() + ':' + deficit.deficitUnits());
        aeron.command(CoreMessageType.RESOLVE_LIQUIDATION, commandId,
                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                        deficit.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, coverUnits)));
        long balance = aeron.balance(deficit.asset());
        coverageRepository.insertCompleted(coverageId, accountType(), deficit, coverUnits, remainingDeficit, now);
        boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"), accountType(), deficit.asset(),
                Math.negateExact(coverUnits), balance, "DEFICIT_COVERAGE", Long.toString(deficit.liquidationId()),
                "COVER_LIQUIDATION_DEFICIT", now);
        if (!inserted) {
            requireReferenceMatches("DEFICIT_COVERAGE", Long.toString(deficit.liquidationId()), accountType(),
                    deficit.asset(), Math.negateExact(coverUnits), "COVER_LIQUIDATION_DEFICIT");
        }
        return true;
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
    }

    private void requireReferenceMatches(String referenceType,
                                         String referenceId,
                                         String accountType,
                                         String asset,
                                         long amountUnits,
                                         String reason) {
        InsuranceLedgerReference existing = ledgerRepository
                .findReference(referenceType, referenceId, accountType, asset)
                .orElseThrow(() -> new IllegalStateException(
                        "duplicate insurance reference but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate insurance fund reference " + referenceId);
        }
    }

    private String accountType() {
        return normalizeAccountType(properties.getKafka().getAccountType());
    }

    private void requireProviderAccountType(String eventAccountType) {
        String providerAccountType = accountType();
        if (!Objects.equals(eventAccountType, providerAccountType)) {
            throw new IllegalArgumentException("liquidation fee account type " + eventAccountType
                    + " does not match insurance provider account type " + providerAccountType);
        }
    }

    private String normalizeAccountType(String accountType) {
        return accountType == null || accountType.isBlank()
                ? DEFAULT_ACCOUNT_TYPE
                : accountType.trim().toUpperCase();
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

    private String normalizeReferenceId(String referenceId) {
        if (referenceId == null || referenceId.isBlank()) {
            throw new IllegalArgumentException("referenceId is required");
        }
        String normalized = referenceId.trim();
        if (normalized.length() > 128) {
            throw new IllegalArgumentException("referenceId length must be <= 128");
        }
        return normalized;
    }

    private int normalizeLimit(int limit) {
        if (limit < 1 || limit > 1000) {
            throw new IllegalArgumentException("limit must be in [1, 1000]");
        }
        return limit;
    }

    public record CoverageCycle(boolean enabled, int resolutions, int covered, int unresolved) {
        static CoverageCycle disabled() {
            return new CoverageCycle(false, 0, 0, 0);
        }
    }
}
