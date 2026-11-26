package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.product.api.ProductLine;
import java.util.UUID;

/** 本次已提交尝试的现金净差额及原始事后总额，使用十进制整数字符串。 */
public record CommittedBalanceFlow(ProductLine product, long userId, UUID commandId, String asset,
                                   String amountUnits, String balanceAfterUnits, long clusterPosition, long occurredAt,
                                   TransferFundsCommand transfer) {}
