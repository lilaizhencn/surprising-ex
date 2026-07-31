package com.surprising.insurance.provider.service;

import com.surprising.account.api.model.AccountUserCommand;
import com.surprising.account.api.model.AccountUserCommandType;
import com.surprising.account.api.model.DeficitReservationAccountCommand;
import com.surprising.account.api.model.LiquidationFeeSettledEvent;
import com.surprising.insurance.api.model.InsuranceCoverageQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundAdjustmentRequest;
import com.surprising.insurance.api.model.InsuranceFundBalanceQueryResponse;
import com.surprising.insurance.api.model.InsuranceFundBalanceResponse;
import com.surprising.insurance.api.model.InsuranceLedgerQueryResponse;
import com.surprising.insurance.provider.config.InsuranceProperties;
import com.surprising.insurance.provider.model.InsuranceDeficitRow;
import com.surprising.insurance.provider.model.InsuranceFundBalanceState;
import com.surprising.insurance.provider.model.InsuranceLedgerReference;
import com.surprising.insurance.provider.repository.InsuranceAccountOutboxRepository;
import com.surprising.insurance.provider.repository.InsuranceCoverageRepository;
import com.surprising.insurance.provider.repository.InsuranceFundBalanceRepository;
import com.surprising.insurance.provider.repository.InsuranceFundLedgerRepository;
import com.surprising.insurance.provider.repository.InsuranceLegacyDeficitRepository;
import com.surprising.insurance.provider.repository.InsuranceProductDeficitRepository;
import com.surprising.insurance.provider.repository.InsuranceSequenceRepository;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

@Service
public class InsuranceService {

    private static final String DEFAULT_ACCOUNT_TYPE = "USDT_PERPETUAL";

    private final InsuranceProperties properties;
    private final InsuranceSequenceRepository sequenceRepository;
    private final InsuranceFundBalanceRepository balanceRepository;
    private final InsuranceFundLedgerRepository ledgerRepository;
    private final InsuranceCoverageRepository coverageRepository;
    private final InsuranceProductDeficitRepository productDeficitRepository;
    private final InsuranceLegacyDeficitRepository legacyDeficitRepository;
    private final InsuranceAccountOutboxRepository accountOutboxRepository;
    private final ObjectMapper objectMapper;

    public InsuranceService(InsuranceProperties properties,
                            InsuranceSequenceRepository sequenceRepository,
                            InsuranceFundBalanceRepository balanceRepository,
                            InsuranceFundLedgerRepository ledgerRepository,
                            InsuranceCoverageRepository coverageRepository,
                            InsuranceProductDeficitRepository productDeficitRepository,
                            InsuranceLegacyDeficitRepository legacyDeficitRepository,
                            InsuranceAccountOutboxRepository accountOutboxRepository,
                            ObjectMapper objectMapper) {
        this.properties = properties;
        this.sequenceRepository = sequenceRepository;
        this.balanceRepository = balanceRepository;
        this.ledgerRepository = ledgerRepository;
        this.coverageRepository = coverageRepository;
        this.productDeficitRepository = productDeficitRepository;
        this.legacyDeficitRepository = legacyDeficitRepository;
        this.accountOutboxRepository = accountOutboxRepository;
        this.objectMapper = objectMapper;
    }

    /**
     * 周期性覆盖账户结算产生的穿仓缺口；同一事务包含基金余额锁、预留、覆盖记录和账户命令 outbox。
     */
    @Transactional
    @Scheduled(fixedDelayString = "${surprising.insurance.coverage.scan-delay-ms:1000}")
    public void coverDeficits() {
        if (!properties.getCoverage().isEnabled()) {
            return;
        }
        int batchSize = properties.getCoverage().getBatchSize();
        String accountType = accountType();
        List<InsuranceDeficitRow> deficits = properties.getKafka().isProductTopicsEnabled()
                ? productDeficitRepository.findPositive(accountType, batchSize)
                : legacyDeficitRepository.findPositive(accountType, batchSize);
        for (InsuranceDeficitRow deficit : deficits) {
            coverDeficit(deficit);
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
        balanceRepository.ensure(accountType, asset, now);
        InsuranceFundBalanceState current = balanceRepository.lock(accountType, asset);
        long nextBalance = Math.addExact(current.balanceUnits(), request.amountUnits());
        if (nextBalance < current.reservedUnits()) {
            throw new IllegalArgumentException("insufficient insurance fund balance");
        }
        boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"),
                accountType, asset, request.amountUnits(), nextBalance,
                "FUND_ADJUSTMENT", referenceId, request.reason(), now);
        if (!inserted) {
            requireReferenceMatches("FUND_ADJUSTMENT", referenceId, accountType, asset,
                    request.amountUnits(), request.reason());
            return balanceRepository.findOne(accountType, asset).orElseThrow();
        }
        balanceRepository.updateBalance(accountType, asset, nextBalance, now);
        return new InsuranceFundBalanceResponse(asset, nextBalance, now);
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
        balanceRepository.ensure(accountType, event.asset(), now);
        InsuranceFundBalanceState current = balanceRepository.lock(accountType, event.asset());
        long nextBalance = Math.addExact(current.balanceUnits(), event.amountUnits());
        boolean inserted = ledgerRepository.insert(sequenceRepository.next("insurance-ledger"),
                accountType, event.asset(), event.amountUnits(), nextBalance,
                "LIQUIDATION_FEE", referenceId, "COLLECT_LIQUIDATION_FEE", now);
        if (!inserted) {
            requireReferenceMatches("LIQUIDATION_FEE", referenceId, accountType, event.asset(),
                    event.amountUnits(), "COLLECT_LIQUIDATION_FEE");
            return;
        }
        balanceRepository.updateBalance(accountType, event.asset(), nextBalance, now);
    }

    public InsuranceFundBalanceQueryResponse balances(String asset) {
        var rows = balanceRepository.find(
                accountType(), asset == null || asset.isBlank() ? null : normalizeAsset(asset));
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

    private boolean coverDeficit(InsuranceDeficitRow deficit) {
        Instant now = Instant.now();
        balanceRepository.ensure(deficit.accountType(), deficit.asset(), now);
        InsuranceFundBalanceState fund = balanceRepository.lock(deficit.accountType(), deficit.asset());
        long availableFund = Math.subtractExact(fund.balanceUnits(), fund.reservedUnits());
        long coverUnits = InsuranceMath.coverAmount(deficit.deficitUnits(), availableFund);
        if (coverUnits <= 0) {
            return false;
        }
        long coverageId = sequenceRepository.next("insurance-coverage");
        long remainingDeficit = Math.subtractExact(deficit.deficitUnits(), coverUnits);
        String commandPrefix = properties.getKafka().getProductLine().name() + ":" + coverageId;
        String reserveCommandId = "INSURANCE_RESERVE:" + commandPrefix;
        String finalizeCommandId = "INSURANCE_FINALIZE:" + commandPrefix;
        balanceRepository.reserve(deficit.accountType(), deficit.asset(), coverUnits, now);
        coverageRepository.insert(coverageId, deficit, coverUnits, remainingDeficit,
                reserveCommandId, finalizeCommandId, now);
        DeficitReservationAccountCommand payload =
                new DeficitReservationAccountCommand(deficit.asset(), coverUnits);
        accountOutboxRepository.enqueue(coverageId, accountCommand(
                reserveCommandId, deficit.userId(), AccountUserCommandType.INSURANCE_DEFICIT_RESERVE,
                coverageId, null, payload, now), now);
        accountOutboxRepository.enqueue(coverageId, accountCommand(
                finalizeCommandId, deficit.userId(), AccountUserCommandType.INSURANCE_DEFICIT_FINALIZE,
                coverageId, reserveCommandId, payload, now), now);
        return true;
    }

    private AccountUserCommand accountCommand(String commandId,
                                              long userId,
                                              AccountUserCommandType type,
                                              long coverageId,
                                              String dependency,
                                              Object payload,
                                              Instant now) {
        return new AccountUserCommand(
                AccountUserCommand.CURRENT_SCHEMA_VERSION,
                commandId,
                properties.getKafka().getProductLine(),
                userId,
                type,
                "INSURANCE",
                Long.toString(coverageId),
                dependency,
                objectMapper.writeValueAsString(payload),
                now,
                null);
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
        if (!properties.getKafka().isProductTopicsEnabled()) {
            return;
        }
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
}
