package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductTransferResponse;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AccountSequenceRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import java.time.Instant;
import java.util.Objects;
import org.springframework.transaction.annotation.Transactional;

/** 旧数据库余额写入实现；生产余额调整统一提交账户用户分区命令。 */
@Deprecated(forRemoval = true)
public class AccountBalanceCommandService {

    private final AccountSequenceRepository sequenceRepository;
    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final ProductTransferRepository productTransferRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final AccountQueryService accountQueryService;

    public AccountBalanceCommandService(AccountSequenceRepository sequenceRepository,
                                        AccountLedgerRepository accountLedgerRepository,
                                        ProductLedgerRepository productLedgerRepository,
                                        ProductTransferRepository productTransferRepository,
                                        AccountBalanceRepository accountBalanceRepository,
                                        ProductBalanceRepository productBalanceRepository,
                                        AccountQueryService accountQueryService) {
        this.sequenceRepository = sequenceRepository;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.productTransferRepository = productTransferRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.accountQueryService = accountQueryService;
    }

    @Transactional
    public BalanceResponse adjustBalance(long userId,
                                         String asset,
                                         long amountUnits,
                                         String referenceId,
                                         String reason) {
        Instant now = Instant.now();
        int ledgerRows = accountLedgerRepository.insertBalanceAdjustment(
                sequenceRepository.nextLedgerEntryId(),
                userId, asset, amountUnits, referenceId, reason, now);
        if (ledgerRows == 0) {
            requireDuplicateBalanceAdjustmentMatches(userId, asset, amountUnits, referenceId, reason);
            return accountQueryService.balance(userId, asset)
                    .orElseThrow(() -> new IllegalStateException(
                            "duplicate balance adjustment but balance missing"));
        }
        accountBalanceRepository.applyAvailableDelta(userId, asset, amountUnits, now);
        BalanceResponse updated = accountQueryService.balance(userId, asset)
                .orElseThrow(() -> new IllegalStateException("balance not found after adjustment"));
        int ledgerRowsAfter = accountLedgerRepository.updateBalanceAdjustmentBalance(
                userId, asset, referenceId, updated.availableUnits());
        requireSingleRow(ledgerRowsAfter, "balance adjustment ledger update");
        return updated;
    }

    @Transactional
    public ProductBalanceResponse adjustProductBalance(long userId,
                                                       AccountType accountType,
                                                       String asset,
                                                       long amountUnits,
                                                       String referenceId,
                                                       String reason) {
        AccountType normalizedType = requireAccountType(accountType);
        if (isLegacyPerpetualAccount(normalizedType)) {
            BalanceResponse updated = adjustBalance(userId, asset, amountUnits,
                    normalizedType.name() + ":" + referenceId, reason);
            return toProductBalance(normalizedType, updated);
        }
        Instant now = Instant.now();
        int ledgerRows = productLedgerRepository.insertBalanceAdjustment(
                sequenceRepository.nextProductLedgerEntryId(),
                userId, normalizedType, asset, amountUnits, referenceId, reason, now);
        if (ledgerRows == 0) {
            requireDuplicateProductBalanceAdjustmentMatches(
                    userId, normalizedType, asset, amountUnits, referenceId, reason);
            return accountQueryService.productBalance(userId, normalizedType, asset)
                    .orElseThrow(() -> new IllegalStateException(
                            "duplicate product adjustment but balance missing"));
        }
        long nextAvailable = productBalanceRepository.applyAvailableDelta(
                userId, normalizedType, asset, amountUnits, now);
        int ledgerRowsAfter = productLedgerRepository.updateBalanceAdjustmentBalance(
                userId, normalizedType, asset, referenceId, nextAvailable);
        requireSingleRow(ledgerRowsAfter, "product balance adjustment ledger update");
        return accountQueryService.productBalance(userId, normalizedType, asset)
                .orElseThrow(() -> new IllegalStateException("product balance not found after adjustment"));
    }

    @Transactional
    public ProductTransferResponse transferProductBalance(long userId,
                                                          AccountType sourceAccountType,
                                                          AccountType targetAccountType,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AccountType source = requireAccountType(sourceAccountType);
        AccountType target = requireAccountType(targetAccountType);
        if (source == target) {
            throw new IllegalArgumentException("source and target account types must be different");
        }
        if (amountUnits <= 0) {
            throw new IllegalArgumentException("amountUnits must be positive");
        }
        Instant now = Instant.now();
        long transferId = sequenceRepository.nextProductTransferId();
        int rows = productTransferRepository.insert(
                transferId, userId, source, target, asset, amountUnits, referenceId, reason, now);
        if (rows == 0) {
            return duplicateProductTransfer(userId, source, target, asset, amountUnits, referenceId, reason);
        }

        long sourceAfter = applyProductAvailableDelta(
                userId, source, asset, Math.negateExact(amountUnits), now);
        long targetAfter = applyProductAvailableDelta(userId, target, asset, amountUnits, now);
        insertProductTransferLedger(userId, source, asset, Math.negateExact(amountUnits), sourceAfter,
                referenceId + ":OUT", reason, now);
        insertProductTransferLedger(userId, target, asset, amountUnits, targetAfter,
                referenceId + ":IN", reason, now);

        return new ProductTransferResponse(transferId, userId, source, target, asset, amountUnits, referenceId,
                "COMPLETED",
                accountQueryService.productBalance(userId, source, asset)
                        .orElseThrow(() -> new IllegalStateException("source balance missing after transfer")),
                accountQueryService.productBalance(userId, target, asset)
                        .orElseThrow(() -> new IllegalStateException("target balance missing after transfer")),
                now);
    }

    private ProductTransferResponse duplicateProductTransfer(long userId,
                                                             AccountType source,
                                                             AccountType target,
                                                             String asset,
                                                             long amountUnits,
                                                             String referenceId,
                                                             String reason) {
        ProductTransferRepository.TransferRecord existing = productTransferRepository
                .findByReference(userId, referenceId)
                .orElseThrow(() -> new IllegalStateException(
                        "duplicate product transfer but transfer row missing"));
        if (existing.sourceAccountType() != source
                || existing.targetAccountType() != target
                || existing.amountUnits() != amountUnits
                || !Objects.equals(existing.asset(), asset)
                || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate product transfer reference " + referenceId);
        }
        return new ProductTransferResponse(existing.transferId(), userId, source, target, asset, amountUnits,
                referenceId, existing.status(),
                accountQueryService.productBalance(userId, source, asset)
                        .orElseThrow(() -> new IllegalStateException(
                                "source balance missing for duplicate transfer")),
                accountQueryService.productBalance(userId, target, asset)
                        .orElseThrow(() -> new IllegalStateException(
                                "target balance missing for duplicate transfer")),
                existing.createdAt());
    }

    private void requireDuplicateBalanceAdjustmentMatches(long userId,
                                                          String asset,
                                                          long amountUnits,
                                                          String referenceId,
                                                          String reason) {
        AccountLedgerRepository.AdjustmentReference existing = accountLedgerRepository
                .findBalanceAdjustment(userId, asset, referenceId)
                .orElseThrow(() -> new IllegalStateException(
                        "duplicate balance adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException("conflicting duplicate balance adjustment reference " + referenceId);
        }
    }

    private void requireDuplicateProductBalanceAdjustmentMatches(long userId,
                                                                 AccountType accountType,
                                                                 String asset,
                                                                 long amountUnits,
                                                                 String referenceId,
                                                                 String reason) {
        ProductLedgerRepository.AdjustmentReference existing = productLedgerRepository
                .findBalanceAdjustment(userId, accountType, asset, referenceId)
                .orElseThrow(() -> new IllegalStateException(
                        "duplicate product adjustment but ledger missing"));
        if (existing.amountUnits() != amountUnits || !Objects.equals(existing.reason(), reason)) {
            throw new IllegalStateException(
                    "conflicting duplicate product balance adjustment reference " + referenceId);
        }
    }

    private long applyProductAvailableDelta(long userId,
                                            AccountType accountType,
                                            String asset,
                                            long amountUnits,
                                            Instant now) {
        if (isLegacyPerpetualAccount(accountType)) {
            return accountBalanceRepository.applyAvailableDelta(userId, asset, amountUnits, now);
        }
        return productBalanceRepository.applyAvailableDelta(userId, accountType, asset, amountUnits, now);
    }

    private void insertProductTransferLedger(long userId,
                                             AccountType accountType,
                                             String asset,
                                             long amountUnits,
                                             long balanceAfterUnits,
                                             String referenceId,
                                             String reason,
                                             Instant now) {
        int rows = productLedgerRepository.insertTransfer(
                sequenceRepository.nextProductLedgerEntryId(),
                userId, accountType, asset, amountUnits, balanceAfterUnits, referenceId, reason, now);
        requireSingleRow(rows, "product transfer ledger insert");
    }

    private static ProductBalanceResponse toProductBalance(AccountType accountType, BalanceResponse balance) {
        return new ProductBalanceResponse(balance.userId(), accountType, balance.asset(), balance.availableUnits(),
                balance.lockedUnits(), balance.equityUnits(), balance.updatedAt());
    }

    private static AccountType requireAccountType(AccountType accountType) {
        if (accountType == null) {
            throw new IllegalArgumentException("accountType is required");
        }
        return accountType;
    }

    private static boolean isLegacyPerpetualAccount(AccountType accountType) {
        return accountType == AccountType.USDT_PERPETUAL;
    }

    private static void requireSingleRow(int rows, String operation) {
        if (rows != 1) {
            throw new IllegalStateException(operation + " affected " + rows + " rows");
        }
    }
}
