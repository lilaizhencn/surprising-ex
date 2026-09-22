package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountLedgerEntryResponse;
import com.surprising.account.api.model.AdminBalanceAdjustmentRecord;
import com.surprising.account.api.model.AdminCursorPage;
import com.surprising.account.api.model.ProductLedgerEntryResponse;
import com.surprising.account.api.model.ProductTransferRecordResponse;
import com.surprising.account.api.model.AccountType;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import org.springframework.stereotype.Service;

/**
 * 账户异步投影查询入口。
 *
 * <p>余额、负债、持仓和仓位模式由 {@link AccountService} 读取本地用户状态快照；本服务只保留
 * 账本、转账记录和后台审计查询，避免任何调用者误把数据库余额投影当成实时事实源。</p>
 */
@Service
public class AccountQueryService {

    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final ProductTransferRepository productTransferRepository;
    private final AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository;

    public AccountQueryService(AccountLedgerRepository accountLedgerRepository,
                               ProductLedgerRepository productLedgerRepository,
                               ProductTransferRepository productTransferRepository,
                               AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository) {
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.productTransferRepository = productTransferRepository;
        this.adminBalanceAdjustmentRepository = adminBalanceAdjustmentRepository;
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
        return adminBalanceAdjustmentRepository.page(adminUserId, userId, adjustmentKind, accountType, asset,
                referenceId, limit, cursor, sort);
    }
}
