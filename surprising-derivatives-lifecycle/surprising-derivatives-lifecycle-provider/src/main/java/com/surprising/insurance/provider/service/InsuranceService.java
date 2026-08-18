package com.surprising.insurance.provider.service;

import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.aeron.client.AeronLifecycleCoordinator;
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
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.stereotype.Service;
import java.nio.charset.StandardCharsets;
import java.util.UUID;

@Service
    public class InsuranceService {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final InsuranceProperties properties;
    private final InsuranceFundLedgerRepository ledgerRepository;
    private final InsuranceCoverageRepository coverageRepository;
    private final InsuranceAeronGateway aeron;
    private final AeronLifecycleCoordinator lifecycleCoordinator = AeronLifecycleCoordinator.shared();
    public InsuranceService(InsuranceProperties properties,
                            InsuranceFundLedgerRepository ledgerRepository,
                            InsuranceCoverageRepository coverageRepository,
                            InsuranceAeronGateway aeron) {
        this.properties = properties;
        this.ledgerRepository = ledgerRepository;
        this.coverageRepository = coverageRepository;
        this.aeron = aeron;
    }

    public synchronized CoverageCycle coverDeficits() {
        return lifecycleCoordinator.execute(this::coverDeficitsInternal);
    }

    private CoverageCycle coverDeficitsInternal() {
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

    public InsuranceFundBalanceResponse adjustFund(InsuranceFundAdjustmentRequest request) {
        if (request.amountUnits() == 0) {
            throw new IllegalArgumentException("amountUnits must not be zero");
        }
        String asset = normalizeAsset(request.asset());
        String referenceId = normalizeReferenceId(request.referenceId());
        Instant now = Instant.now();
        long currentBalance = aeron.balance(asset);
        long nextBalance = Math.addExact(currentBalance, request.amountUnits());
        if (nextBalance < 0) throw new IllegalArgumentException("insufficient insurance fund balance");
        UUID commandId = stableId("INSURANCE_FUND:" + properties.getKafka().getProductLine() + ':' + referenceId);
        aeron.command(CoreMessageType.ADJUST_INSURANCE_FUND, commandId,
                TradingCommandCodec.encodeAdjustInsuranceFund(
                        new AdjustInsuranceFundCommand(asset, request.amountUnits())));
        long committedBalance = aeron.balance(asset);
        return new InsuranceFundBalanceResponse(asset, committedBalance, now);
    }

    public void collectLiquidationFee(LiquidationFeeSettledEvent event) {
        if (event.amountUnits() <= 0) {
            throw new IllegalArgumentException("liquidation fee amountUnits must be positive");
        }
        String accountType = normalizeAccountType(event.accountType());
        requireProviderAccountType(accountType);
        String referenceId = event.tradeId() + ":" + event.orderId();
        String asset = normalizeAsset(event.asset());
        UUID commandId = stableId("LIQUIDATION_FEE:" + properties.getKafka().getProductLine() + ':' + referenceId);
        aeron.command(CoreMessageType.ADJUST_INSURANCE_FUND, commandId,
                TradingCommandCodec.encodeAdjustInsuranceFund(
                        new AdjustInsuranceFundCommand(asset, event.amountUnits())));
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
        long availableFund = aeron.balance(deficit.asset());
        long coverUnits = InsuranceMath.coverAmount(deficit.deficitUnits(), availableFund);
        if (coverUnits <= 0) {
            return false;
        }
        UUID commandId = stableId("INSURANCE_COVER:" + properties.getKafka().getProductLine() + ':'
                + deficit.liquidationId() + ':' + deficit.deficitUnits());
        aeron.command(CoreMessageType.RESOLVE_LIQUIDATION, commandId,
                TradingCommandCodec.encodeResolveLiquidation(new ResolveLiquidationCommand(
                        deficit.liquidationId(), ResolveLiquidationCommand.Resolution.INSURANCE, coverUnits)));
        return true;
    }

    private static UUID stableId(String value) {
        return UUID.nameUUIDFromBytes(value.getBytes(StandardCharsets.UTF_8));
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
