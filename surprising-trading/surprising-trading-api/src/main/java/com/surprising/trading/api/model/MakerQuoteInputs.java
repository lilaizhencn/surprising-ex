package com.surprising.trading.api.model;

import com.surprising.product.api.InstrumentIds;
import com.surprising.product.api.ProductLine;
import java.util.List;

/** A single quote cycle's inventory and authoritative effective fees; never a cross-cycle cache. */
public final class MakerQuoteInputs {
    private MakerQuoteInputs() {}

    public record Request(ProductLine productLine, String instrumentId, long instrumentChangeId,
                          MarginMode marginMode, List<Long> accountIds) {
        public Request {
            if (productLine == null || marginMode == null || instrumentChangeId <= 0)
                throw new IllegalArgumentException("product, margin mode and instrument version are required");
            InstrumentIds.parse(instrumentId);
            if (accountIds == null || accountIds.isEmpty() || accountIds.size() > 64
                    || accountIds.stream().anyMatch(id -> id == null || id <= 0)
                    || accountIds.stream().distinct().count() != accountIds.size())
                throw new IllegalArgumentException("requires 1-64 unique positive account IDs");
            accountIds = List.copyOf(accountIds);
        }
    }
    public record Account(long userId, long signedInventorySteps, long makerFeeRatePpm) {}
    public record Response(ProductLine productLine, String instrumentId, long instrumentChangeId,
                           List<Account> accounts) {
        public Response { accounts = List.copyOf(accounts); }
    }
}
