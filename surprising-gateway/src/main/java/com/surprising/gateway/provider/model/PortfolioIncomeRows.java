package com.surprising.gateway.provider.model;

import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.Map;

/** 已提交收益视图的原生行；不是可花费余额或在线账户状态。 */
public final class PortfolioIncomeRows {
    private PortfolioIncomeRows() { }
    public record Cut(ProductLine product, long time, long position, long stateSequence, long coveredFrom,
                      boolean opening, boolean complete, Map<String, BigInteger> units) {}
    public record Flow(ProductLine product, long position, long time, String asset, BigInteger units,
                       Long transferId, ProductLine sourceProduct, ProductLine targetProduct,
                       long sourceUser, long targetUser, String commandId) {}
    public record Price(long ticks, long tickUnits, long quoteScaleUnits, long occurredAt) {}
}
