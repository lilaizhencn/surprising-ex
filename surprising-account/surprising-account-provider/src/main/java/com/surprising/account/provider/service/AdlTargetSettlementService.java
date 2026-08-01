package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdlTargetSettlementAccountCommand;
import com.surprising.account.api.model.OpenInterestShardSnapshot;
import com.surprising.account.provider.model.ContractSpec;
import com.surprising.account.provider.model.PositionState;
import com.surprising.instrument.api.cache.InstrumentSnapshotCache;
import com.surprising.instrument.api.model.InstrumentResponse;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.OpenInterestShardRepository;
import com.surprising.account.provider.repository.PositionMarginRepository;
import com.surprising.account.provider.repository.PositionRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductDeficitRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.instrument.api.math.PerpetualContractMath;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.time.Instant;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;

/** 在满足快照条件时结算 ADL 盈利目标侧。 */
@Service
public class AdlTargetSettlementService {

    private final AccountSequenceRepository sequenceRepository;
    private final PositionRepository positionRepository;
    private final OpenInterestShardRepository openInterestShardRepository;
    private final InstrumentSnapshotCache snapshotCache;
    private final PositionMarginRepository positionMarginRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final AccountDeficitRepository accountDeficitRepository;
    private final ProductDeficitRepository productDeficitRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final AccountOutboxService accountOutboxService;

    public AdlTargetSettlementService(AccountSequenceRepository sequenceRepository,
                                      PositionRepository positionRepository,
                                      OpenInterestShardRepository openInterestShardRepository,
                                      InstrumentSnapshotCache snapshotCache,
                                      PositionMarginRepository positionMarginRepository,
                                      AccountBalanceRepository accountBalanceRepository,
                                      ProductBalanceRepository productBalanceRepository,
                                      AccountDeficitRepository accountDeficitRepository,
                                      ProductDeficitRepository productDeficitRepository,
                                      AccountLedgerRepository accountLedgerRepository,
                                      ProductLedgerRepository productLedgerRepository) {
        this(sequenceRepository, positionRepository, openInterestShardRepository, snapshotCache,
                positionMarginRepository, accountBalanceRepository, productBalanceRepository,
                accountDeficitRepository, productDeficitRepository, accountLedgerRepository,
                productLedgerRepository, null);
    }

    @Autowired
    public AdlTargetSettlementService(AccountSequenceRepository sequenceRepository,
                                      PositionRepository positionRepository,
                                      OpenInterestShardRepository openInterestShardRepository,
                                      InstrumentSnapshotCache snapshotCache,
                                      PositionMarginRepository positionMarginRepository,
                                      AccountBalanceRepository accountBalanceRepository,
                                      ProductBalanceRepository productBalanceRepository,
                                      AccountDeficitRepository accountDeficitRepository,
                                      ProductDeficitRepository productDeficitRepository,
                                      AccountLedgerRepository accountLedgerRepository,
                                      ProductLedgerRepository productLedgerRepository,
                                      AccountOutboxService accountOutboxService) {
        this.sequenceRepository = sequenceRepository;
        this.positionRepository = positionRepository;
        this.openInterestShardRepository = openInterestShardRepository;
        this.snapshotCache = snapshotCache;
        this.positionMarginRepository = positionMarginRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.accountDeficitRepository = accountDeficitRepository;
        this.productDeficitRepository = productDeficitRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.accountOutboxService = accountOutboxService;
    }

    @Transactional
    public AdlTargetSettlementResult settle(ProductLine productLine,
                                            long targetUserId,
                                            String commandId,
                                            AdlTargetSettlementAccountCommand command,
                                            Instant now) {
        if (!productLine.isFundingProduct()) {
            throw new IllegalArgumentException("ADL target settlement requires a perpetual product line");
        }
        AdlPosition target;
        try {
            target = lockTarget(productLine, targetUserId, command);
        } catch (StaleAdlTargetException ex) {
            return AdlTargetSettlementResult.stale();
        }
        PositionState position = target.position();
        if (position.signedQuantitySteps() != command.expectedSignedQuantitySteps()
                || position.entryPriceTicks() != command.expectedEntryPriceTicks()) {
            return AdlTargetSettlementResult.stale();
        }
        long absQuantity = Math.absExact(position.signedQuantitySteps());
        if (command.closeQuantitySteps() > absQuantity) {
            return AdlTargetSettlementResult.stale();
        }
        ContractSpec spec = target.contractSpec();
        long fullProfit = Math.max(0L, PerpetualContractMath.unrealizedPnlUnits(
                spec.contractType(), position.signedQuantitySteps(), position.entryPriceTicks(),
                command.markPriceTicks(), spec.notionalMultiplierUnits(), spec.priceTickUnits(),
                spec.settleScaleUnits()));
        long realizedProfit = proportional(fullProfit, command.closeQuantitySteps(), absQuantity);
        if (realizedProfit != command.expectedRealizedProfitUnits()
                || command.coveredUnits() > realizedProfit
                || hasDeficit(productLine, targetUserId, command.asset())) {
            return AdlTargetSettlementResult.stale();
        }

        long nextAbs = Math.subtractExact(absQuantity, command.closeQuantitySteps());
        long nextSigned = position.signedQuantitySteps() > 0 ? nextAbs : Math.negateExact(nextAbs);
        updatePositionAndOpenInterest(
                productLine, targetUserId, command, position, nextSigned, realizedProfit, now);
        releasePositionMargin(productLine, targetUserId, command, absQuantity, now);
        long afterProfit = creditAvailable(productLine, targetUserId, command.asset(), realizedProfit, now);
        insertLedger(productLine, targetUserId, command.asset(), realizedProfit, afterProfit,
                "ADL_REALIZED_PNL", commandId, "ADL_POSITION_DELEVERAGED", now);
        long afterTransfer = debitAvailable(
                productLine, targetUserId, command.asset(), command.coveredUnits(), now);
        insertLedger(productLine, targetUserId, command.asset(), Math.negateExact(command.coveredUnits()),
                afterTransfer, "ADL_TRANSFER", commandId, "ADL_DEFICIT_TRANSFER", now);
        return new AdlTargetSettlementResult(true, realizedProfit, command.coveredUnits(), nextSigned);
    }

    private AdlPosition lockTarget(ProductLine productLine,
                                   long userId,
                                   AdlTargetSettlementAccountCommand command) {
        PositionState position = positionRepository.lock(
                        productLine, userId, command.symbol(), command.marginMode(), command.positionSide())
                .orElseThrow(() -> new StaleAdlTargetException("ADL target position is missing"));
        InstrumentResponse instrument = snapshotCache.version(productLine, command.symbol(), position.instrumentVersion())
                .filter(value -> value.contractType() != com.surprising.instrument.api.model.ContractType.SPOT)
                .orElseThrow(() -> new StaleAdlTargetException("ADL target instrument is missing"));
        if (!command.asset().equals(instrument.settleAsset())) {
            throw new StaleAdlTargetException("ADL target settle asset changed");
        }
        long scaleUnits = snapshotCache.scale(productLine, command.asset())
                .orElseThrow(() -> new StaleAdlTargetException("ADL target asset scale is missing"));
        ContractSpec spec = new ContractSpec(
                instrument.version(), instrument.contractType(), instrument.settleAsset(),
                instrument.notionalMultiplierUnits(), instrument.priceTickUnits(), scaleUnits,
                instrument.initialMarginRatePpm(), instrument.makerFeeRatePpm(), instrument.takerFeeRatePpm());
        return new AdlPosition(position, spec);
    }

    private void updatePositionAndOpenInterest(ProductLine productLine,
                                               long userId,
                                               AdlTargetSettlementAccountCommand command,
                                               PositionState position,
                                               long nextSigned,
                                               long realizedProfit,
                                               Instant now) {
        long nextEntryValue = nextSigned == 0 ? 0L : Math.subtractExact(position.entryValueTicks(),
                proportional(position.entryValueTicks(), command.closeQuantitySteps(),
                        Math.absExact(position.signedQuantitySteps())));
        PositionState nextPosition = new PositionState(
                nextSigned,
                nextSigned == 0 ? 0L : position.instrumentVersion(),
                nextSigned == 0 ? 0L : position.entryPriceTicks(),
                nextEntryValue,
                Math.addExact(position.realizedPnlUnits(), realizedProfit));
        requireSingleRow(positionRepository.update(
                productLine, userId, command.symbol(), command.marginMode(), command.positionSide(),
                nextPosition, now), "ADL target position update");

        long longDelta = Math.subtractExact(
                Math.max(nextSigned, 0L), Math.max(position.signedQuantitySteps(), 0L));
        long previousShort = position.signedQuantitySteps() < 0
                ? Math.negateExact(position.signedQuantitySteps()) : 0L;
        long nextShort = nextSigned < 0 ? Math.negateExact(nextSigned) : 0L;
        long shortDelta = Math.subtractExact(nextShort, previousShort);
        int shardId = OpenInterestShardRepository.shardId(userId);
        int seeded = openInterestShardRepository.seed(productLine, command.symbol(), shardId, now);
        if (seeded < 0 || seeded > 1) {
            throw new IllegalStateException("unexpected ADL open interest shard seed rows: " + seeded);
        }
        OpenInterestShardRepository.OpenInterestShardState snapshot = openInterestShardRepository
                .adjustAndSnapshot(productLine, command.symbol(), shardId, longDelta, shortDelta, now)
                .orElseThrow(() -> new IllegalStateException("ADL target open interest shard update affected 0 rows"));
        if (accountOutboxService != null) {
            accountOutboxService.enqueueOpenInterestUpdated(
                    new OpenInterestShardSnapshot(snapshot.productLine(), snapshot.symbol(), snapshot.shardId(),
                            snapshot.longQuantitySteps(), snapshot.shortQuantitySteps(), snapshot.revision(),
                            snapshot.updatedAt()), now);
        }
    }

    private void releasePositionMargin(ProductLine productLine,
                                       long userId,
                                       AdlTargetSettlementAccountCommand command,
                                       long previousAbsQuantity,
                                       Instant now) {
        long marginUnits = positionMarginRepository.lockUnits(
                productLine, userId, command.symbol(), command.asset(),
                command.marginMode(), command.positionSide());
        long releaseUnits = proportional(marginUnits, command.closeQuantitySteps(), previousAbsQuantity);
        if (releaseUnits <= 0) {
            return;
        }
        int balanceRows = usesProductAccount(productLine)
                ? productBalanceRepository.moveLockedToAvailable(
                        userId, accountType(productLine), command.asset(), releaseUnits, now)
                : accountBalanceRepository.moveLockedToAvailable(
                        userId, command.asset(), releaseUnits, now);
        requireSingleRow(balanceRows, "ADL target margin balance release");
        requireSingleRow(positionMarginRepository.subtract(
                productLine, userId, command.symbol(), command.asset(),
                command.marginMode(), command.positionSide(), releaseUnits, now),
                "ADL target position margin release");
        positionMarginRepository.deleteZero(
                productLine, userId, command.symbol(), command.asset(),
                command.marginMode(), command.positionSide());
    }

    private boolean hasDeficit(ProductLine productLine, long userId, String asset) {
        return usesProductAccount(productLine)
                ? productDeficitRepository.lockUnits(userId, accountType(productLine), asset) > 0
                : accountDeficitRepository.lockUnits(userId, asset) > 0;
    }

    private long creditAvailable(ProductLine productLine,
                                 long userId,
                                 String asset,
                                 long amountUnits,
                                 Instant now) {
        return usesProductAccount(productLine)
                ? productBalanceRepository.creditAvailableAndReturnEquity(
                        userId, accountType(productLine), asset, amountUnits, now)
                : accountBalanceRepository.creditAvailableAndReturnEquity(
                        userId, asset, amountUnits, now);
    }

    private long debitAvailable(ProductLine productLine,
                                long userId,
                                String asset,
                                long amountUnits,
                                Instant now) {
        return usesProductAccount(productLine)
                ? productBalanceRepository.debitAvailableAndReturnEquity(
                        userId, accountType(productLine), asset, amountUnits, now)
                : accountBalanceRepository.debitAvailableAndReturnEquity(
                        userId, asset, amountUnits, now);
    }

    private void insertLedger(ProductLine productLine,
                              long userId,
                              String asset,
                              long amountUnits,
                              long balanceAfter,
                              String referenceType,
                              String commandId,
                              String reason,
                              Instant now) {
        int rows = usesProductAccount(productLine)
                ? productLedgerRepository.insertAdl(
                        sequenceRepository.nextProductLedgerEntryId(), accountType(productLine),
                        userId, asset, amountUnits, balanceAfter, referenceType, commandId, reason, now)
                : accountLedgerRepository.insertAdl(
                        sequenceRepository.nextLedgerEntryId(), userId, asset, amountUnits,
                        balanceAfter, referenceType, commandId, reason, now);
        requireSingleRow(rows, "ADL target ledger insert");
    }

    private long proportional(long units, long numerator, long denominator) {
        return BigInteger.valueOf(units)
                .multiply(BigInteger.valueOf(numerator))
                .divide(BigInteger.valueOf(denominator))
                .longValueExact();
    }

    private void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException("failed to write " + operation);
        }
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }

    private AccountType accountType(ProductLine productLine) {
        return AccountType.valueOf(productLine.accountTypeCode());
    }

    private record AdlPosition(PositionState position, ContractSpec contractSpec) {
    }

    public record AdlTargetSettlementResult(
            boolean applied,
            long realizedProfitUnits,
            long coveredUnits,
            long nextSignedQuantitySteps) {

        private static AdlTargetSettlementResult stale() {
            return new AdlTargetSettlementResult(false, 0L, 0L, 0L);
        }
    }

    private static final class StaleAdlTargetException extends RuntimeException {
        private StaleAdlTargetException(String message) {
            super(message);
        }
    }
}
