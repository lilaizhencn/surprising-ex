package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.BalanceResponse;
import com.surprising.account.api.model.ProductBalanceResponse;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import com.surprising.account.provider.repository.AccountBalanceRepository;
import com.surprising.account.provider.repository.AccountDeficitRepository;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.ProductBalanceRepository;
import com.surprising.account.provider.repository.ProductDeficitRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Service;

@Service
public class AccountQueryService {

    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final ProductTransferRepository productTransferRepository;
    private final AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository;
    private final AccountBalanceRepository accountBalanceRepository;
    private final AccountDeficitRepository accountDeficitRepository;
    private final ProductBalanceRepository productBalanceRepository;
    private final ProductDeficitRepository productDeficitRepository;

    public AccountQueryService(AccountLedgerRepository accountLedgerRepository,
                               ProductLedgerRepository productLedgerRepository,
                               ProductTransferRepository productTransferRepository,
                               AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository,
                               AccountBalanceRepository accountBalanceRepository,
                               AccountDeficitRepository accountDeficitRepository,
                               ProductBalanceRepository productBalanceRepository,
                               ProductDeficitRepository productDeficitRepository) {
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.productTransferRepository = productTransferRepository;
        this.adminBalanceAdjustmentRepository = adminBalanceAdjustmentRepository;
        this.accountBalanceRepository = accountBalanceRepository;
        this.accountDeficitRepository = accountDeficitRepository;
        this.productBalanceRepository = productBalanceRepository;
        this.productDeficitRepository = productDeficitRepository;
    }

    public Optional<BalanceResponse> balance(long userId, String asset) {
        return accountBalanceRepository.find(userId, asset)
                .map(row -> toBalance(row, accountDeficitRepository.findUnits(userId, asset).orElse(0L)));
    }

    public List<BalanceResponse> balances(long userId) {
        Map<String, Long> deficits = new HashMap<>();
        accountDeficitRepository.findByUser(userId)
                .forEach(row -> deficits.put(row.asset(), row.deficitUnits()));
        return accountBalanceRepository.findByUser(userId).stream()
                .map(row -> toBalance(row, deficits.getOrDefault(row.asset(), 0L)))
                .toList();
    }

    public Optional<ProductBalanceResponse> productBalance(long userId,
                                                           AccountType accountType,
                                                           String asset) {
        if (accountType == AccountType.USDT_PERPETUAL) {
            return balance(userId, asset).map(row -> toProductBalance(accountType, row));
        }
        return productBalanceRepository.find(userId, accountType, asset)
                .map(row -> toProductBalance(row,
                        productDeficitRepository.findUnits(userId, accountType, asset).orElse(0L)));
    }

    public List<ProductBalanceResponse> productBalances(long userId, AccountType accountType) {
        if (accountType == AccountType.USDT_PERPETUAL) {
            return balances(userId).stream()
                    .map(row -> toProductBalance(accountType, row))
                    .toList();
        }
        Map<ProductBalanceKey, Long> deficits = new HashMap<>();
        productDeficitRepository.findByUser(userId, accountType)
                .forEach(row -> deficits.put(new ProductBalanceKey(row.accountType(), row.asset()),
                        row.deficitUnits()));
        List<ProductBalanceResponse> isolated = productBalanceRepository.findByUser(userId, accountType).stream()
                .filter(row -> row.accountType() != AccountType.USDT_PERPETUAL)
                .map(row -> toProductBalance(row,
                        deficits.getOrDefault(new ProductBalanceKey(row.accountType(), row.asset()), 0L)))
                .toList();
        if (accountType != null) {
            return isolated;
        }
        List<ProductBalanceResponse> legacy = balances(userId).stream()
                .map(row -> toProductBalance(AccountType.USDT_PERPETUAL, row))
                .toList();
        return java.util.stream.Stream.concat(legacy.stream(), isolated.stream()).toList();
    }

    public AdminCursorPage.CursorPage<AccountLedgerEntryResponse> accountLedgerPage(Long userId,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        return accountLedgerRepository.page(userId, asset, referenceType, limit, cursor, sort);
    }

    public AdminCursorPage.CursorPage<ProductLedgerEntryResponse> productLedgerPage(Long userId,
                                                                                    AccountType accountType,
                                                                                    String asset,
                                                                                    String referenceType,
                                                                                    int limit,
                                                                                    String cursor,
                                                                                    String sort) {
        return productLedgerRepository.page(userId, accountType, asset, referenceType, limit, cursor, sort);
    }

    public AdminCursorPage.CursorPage<ProductTransferRecordResponse> productTransferPage(Long userId,
                                                                                         AccountType accountType,
                                                                                         String asset,
                                                                                         int limit,
                                                                                         String cursor,
                                                                                         String sort) {
        return productTransferRepository.page(userId, accountType, asset, limit, cursor, sort);
    }

    public AdminCursorPage.CursorPage<AdminBalanceAdjustmentRecord> adminBalanceAdjustmentPage(
            Long adminUserId,
            Long userId,
            String adjustmentKind,
            AccountType accountType,
            String asset,
            String referenceId,
            int limit,
            String cursor,
            String sort) {
        return adminBalanceAdjustmentRepository.page(adminUserId, userId, adjustmentKind, accountType,
                asset, referenceId, limit, cursor, sort);
    }

    private static BalanceResponse toBalance(AccountBalanceRepository.BalanceRow row, long deficitUnits) {
        long equityUnits = Math.subtractExact(
                Math.addExact(row.availableUnits(), row.lockedUnits()), deficitUnits);
        return new BalanceResponse(row.userId(), row.asset(), row.availableUnits(), row.lockedUnits(),
                equityUnits, row.updatedAt());
    }

    private static ProductBalanceResponse toProductBalance(AccountType accountType, BalanceResponse row) {
        return new ProductBalanceResponse(row.userId(), accountType, row.asset(), row.availableUnits(),
                row.lockedUnits(), row.equityUnits(), row.updatedAt());
    }

    private static ProductBalanceResponse toProductBalance(
            ProductBalanceRepository.ProductBalanceRow row,
            long deficitUnits) {
        long equityUnits = Math.subtractExact(
                Math.addExact(row.availableUnits(), row.lockedUnits()), deficitUnits);
        return new ProductBalanceResponse(row.userId(), row.accountType(), row.asset(), row.availableUnits(),
                row.lockedUnits(), equityUnits, row.updatedAt());
    }

    private record ProductBalanceKey(AccountType accountType, String asset) {
    }
}
