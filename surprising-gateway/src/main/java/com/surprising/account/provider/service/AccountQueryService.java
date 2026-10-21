package com.surprising.account.provider.service;

import com.surprising.account.api.model.AccountType;
import com.surprising.account.api.model.AccountLedgerQueryResponse;
import com.surprising.account.api.model.ProductLedgerQueryResponse;
import com.surprising.account.api.model.ProductTransferRecordQueryResponse;
import com.surprising.account.api.model.AdminBalanceAdjustmentQueryResponse;
import com.surprising.account.provider.model.AccountHistoryPageQuery;
import com.surprising.account.provider.config.AccountProperties;
import com.surprising.product.api.ProductLine;
import com.surprising.product.api.ProductLineConfiguration;
import com.surprising.account.provider.repository.AccountLedgerRepository;
import com.surprising.account.provider.repository.AdminBalanceAdjustmentRepository;
import com.surprising.account.provider.repository.ProductLedgerRepository;
import com.surprising.account.provider.repository.ProductTransferRepository;
import org.springframework.stereotype.Service;

/**
 * 账户异步投影查询入口。
 *
 * <p>余额、负债、持仓和仓位模式由 {@link AccountService} 读取 realtime 用户视图；本服务只保留
 * 账本、转账记录和后台审计查询，避免任何调用者误把数据库余额投影当成实时事实源。</p>
 */
@Service
public class AccountQueryService {
    private final AccountProperties properties;

    private final AccountLedgerRepository accountLedgerRepository;
    private final ProductLedgerRepository productLedgerRepository;
    private final ProductTransferRepository productTransferRepository;
    private final AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository;

    public AccountQueryService(AccountLedgerRepository accountLedgerRepository,
                               ProductLedgerRepository productLedgerRepository,
                               ProductTransferRepository productTransferRepository,
                               AdminBalanceAdjustmentRepository adminBalanceAdjustmentRepository, AccountProperties properties) {
        this.properties = properties;
        this.accountLedgerRepository = accountLedgerRepository;
        this.productLedgerRepository = productLedgerRepository;
        this.productTransferRepository = productTransferRepository;
        this.adminBalanceAdjustmentRepository = adminBalanceAdjustmentRepository;
    }

    public AccountLedgerQueryResponse accountLedger(Long userId,
                                                    String asset,
                                                    String referenceType,
                                                    AccountHistoryPageQuery pageQuery) {
        var page = accountLedgerRepository.page(userId, asset,
                referenceType, pageQuery);
        return new AccountLedgerQueryResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    public ProductLedgerQueryResponse productLedger(Long userId,
                                                    AccountType accountType,
                                                    String asset,
                                                    String referenceType,
                                                    AccountHistoryPageQuery pageQuery) {
        requireProductAccount(accountType);
        var page = productLedgerRepository.page(userId, accountType,
                asset, referenceType, pageQuery);
        return new ProductLedgerQueryResponse(page.items().size(), page.items(), page.nextCursor(), page.hasMore(),
                page.sort(), page.limit());
    }

    public ProductTransferRecordQueryResponse productTransfers(Long userId,
                                                               AccountType accountType,
                                                               String asset,
                                                               AccountHistoryPageQuery pageQuery) {
        var page = productTransferRepository.page(userId, accountType,
                asset, pageQuery);
        return new ProductTransferRecordQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    public AdminBalanceAdjustmentQueryResponse adminBalanceAdjustments(Long adminUserId,
                                                                       Long userId,
                                                                       String adjustmentKind,
                                                                       AccountType accountType,
                                                                       String asset,
                                                                       String referenceId,
                                                                       AccountHistoryPageQuery pageQuery) {
        if (accountType != null) {
            requireProductAccount(accountType);
        }
        var page = adminBalanceAdjustmentRepository.page(adminUserId, userId,
                adjustmentKind, accountType, asset,
                referenceId, pageQuery);
        return new AdminBalanceAdjustmentQueryResponse(page.items().size(), page.items(), page.nextCursor(),
                page.hasMore(), page.sort(), page.limit());
    }

    private ProductLine currentProductLine() {
        AccountProperties.Kafka kafka = properties == null ? null : properties.getKafka();
        if (kafka == null || kafka.getProductLine() == null) {
            throw new IllegalStateException("account 未配置唯一产品线");
        }
        return kafka.getProductLine();
    }

    private void requireCurrentProduct(ProductLine productLine) {
        ProductLine current = currentProductLine();
        ProductLineConfiguration.requireSame(current, productLine, "account.product-line");
    }

    private void requireProductAccount(AccountType accountType) {
        if (accountType == null || accountType == AccountType.FUNDING
                || accountType.productLine().isEmpty()) {
            throw new IllegalArgumentException("产品账户类型无效: " + accountType);
        }
        requireCurrentProduct(accountType.productLine().orElseThrow());
    }

}
