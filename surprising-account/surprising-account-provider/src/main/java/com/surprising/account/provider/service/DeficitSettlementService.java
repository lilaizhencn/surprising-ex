package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductDeficitRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import org.springframework.transaction.annotation.Transactional;

/** 管理保险基金与 ADL 之间无需跨用户锁的亏空预留。 */
@Deprecated(forRemoval = true)
public class DeficitSettlementService {

    private final AccountSequenceRepository sequenceRepository;
    private final AccountDeficitRepository accountDeficitRepository;
    private final ProductDeficitRepository productDeficitRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;

    public DeficitSettlementService(AccountSequenceRepository sequenceRepository,
                                    AccountDeficitRepository accountDeficitRepository,
                                    ProductDeficitRepository productDeficitRepository,
                                    AccountBalanceRepository accountBalanceRepository,
                                    ProductBalanceRepository productBalanceRepository,
                                    AccountLedgerRepository accountLedgerRepository,
                                    ProductLedgerRepository productLedgerRepository) {
        this.sequenceRepository = sequenceRepository;
        this.accountDeficitRepository = accountDeficitRepository;
        this.productDeficitRepository = productDeficitRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
    }

    @Transactional
    public boolean reserve(ProductLine productLine,
                           long userId,
                           String asset,
                           long amountUnits,
                           Instant now) {
        return usesProductAccount(productLine)
                ? productDeficitRepository.reserve(accountType(productLine), userId, asset, amountUnits, now)
                : accountDeficitRepository.reserve(userId, asset, amountUnits, now);
    }

    @Transactional
    public long finalizeReservation(ProductLine productLine,
                                    long userId,
                                    String asset,
                                    long amountUnits,
                                    String commandId,
                                    String referenceType,
                                    String reason,
                                    Instant now) {
        var remainingResult = usesProductAccount(productLine)
                ? productDeficitRepository.finalizeReservation(
                        accountType(productLine), userId, asset, amountUnits, now)
                : accountDeficitRepository.finalizeReservation(userId, asset, amountUnits, now);
        if (remainingResult.isEmpty()) {
            throw new IllegalStateException("deficit reservation is missing for " + commandId);
        }
        long remaining = remainingResult.getAsLong();
        long balanceAfter = equity(productLine, userId, asset, remaining);
        int ledgerRows = usesProductAccount(productLine)
                ? productLedgerRepository.insertDeficitSettlement(
                        sequenceRepository.nextProductLedgerEntryId(), accountType(productLine), userId, asset,
                        amountUnits, balanceAfter, referenceType, commandId, reason, now)
                : accountLedgerRepository.insertDeficitSettlement(
                        sequenceRepository.nextLedgerEntryId(), userId, asset, amountUnits, balanceAfter,
                        referenceType, commandId, reason, now);
        if (ledgerRows != 1) {
            throw new IllegalStateException("failed to insert deficit settlement ledger " + commandId);
        }
        return remaining;
    }

    @Transactional
    public long releaseReservation(ProductLine productLine,
                                   long userId,
                                   String asset,
                                   long amountUnits,
                                   Instant now) {
        var result = usesProductAccount(productLine)
                ? productDeficitRepository.releaseReservation(
                        accountType(productLine), userId, asset, amountUnits, now)
                : accountDeficitRepository.releaseReservation(userId, asset, amountUnits, now);
        if (result.isEmpty()) {
            throw new IllegalStateException("deficit reservation release is missing");
        }
        return result.getAsLong();
    }

    private long equity(ProductLine productLine, long userId, String asset, long remainingDeficit) {
        long balance = usesProductAccount(productLine)
                ? productBalanceRepository.equity(userId, accountType(productLine), asset)
                : accountBalanceRepository.equity(userId, asset);
        return Math.subtractExact(balance, remainingDeficit);
    }

    private AccountType accountType(ProductLine productLine) {
        return AccountType.valueOf(productLine.accountTypeCode());
    }

    private boolean usesProductAccount(ProductLine productLine) {
        return productLine != ProductLine.LINEAR_PERPETUAL;
    }
}
