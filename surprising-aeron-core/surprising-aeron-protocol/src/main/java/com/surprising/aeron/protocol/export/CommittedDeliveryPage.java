package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.SettleInstrumentCommand;
import com.surprising.product.api.ProductLine;
import java.util.HashSet;
import java.util.List;

/** 当前有界交割页观察到的现金变化，列表复制后对外提供。 */
public record CommittedDeliveryPage(ProductLine product, long occurredAt,
                                    SettleInstrumentCommand command, List<DeliveryCashFlow> cashFlows) {
    public CommittedDeliveryPage {
        cashFlows = cashFlows == null ? List.of() : List.copyOf(cashFlows);
        if (product == null || !product.isDeliveryProduct() || occurredAt <= 0
                || command == null || cashFlows.isEmpty() || cashFlows.size() > command.maxUsers())
            throw new IllegalArgumentException("invalid committed delivery cash page");
        var users = new HashSet<Long>();
        for (var flow : cashFlows) if (!users.add(flow.userId()))
            throw new IllegalArgumentException("duplicate delivery cash user");
    }

    /** 一个交割用户在本页实际发生的非零结算现金差额及结算后总额。 */
    public record DeliveryCashFlow(long userId, String asset, long amountUnits, long balanceAfterUnits) {
        public DeliveryCashFlow {
            if (userId <= 0 || asset == null || asset.isBlank() || amountUnits == 0 || balanceAfterUnits < 0)
                throw new IllegalArgumentException("invalid delivery cash flow");
        }
    }
}
