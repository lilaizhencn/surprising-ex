package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import java.time.Instant;

/**
 * 账户单写者提交后的永续风险钱包完整快照。
 *
 * <p>该事件不是账本事实，而是由账户事务产生的可恢复读模型。风险服务只消费该快照，
 * 不再在实时路径跨表查询余额、欠款、隔离保证金和订单冻结。</p>
 */
public record AccountRiskWalletUpdatedEvent(
        int schemaVersion,
        long eventId,
        long accountRevision,
        ProductLine productLine,
        long userId,
        String accountType,
        String settleAsset,
        long walletBalanceUnits,
        Instant eventTime,
        String traceId) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public AccountRiskWalletUpdatedEvent {
        if (schemaVersion <= 0 || eventId <= 0 || accountRevision <= 0 || productLine == null || userId <= 0
                || accountType == null || accountType.isBlank() || settleAsset == null || settleAsset.isBlank()
                || eventTime == null) {
            throw new IllegalArgumentException("invalid account risk wallet event");
        }
        accountType = accountType.trim().toUpperCase();
        settleAsset = settleAsset.trim().toUpperCase();
    }

    public String partitionKey() {
        return productLine.name() + ":" + userId;
    }
}
