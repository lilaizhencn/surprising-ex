package com.surprising.aeron.protocol.export;

import com.surprising.product.api.ProductLine;
import java.util.Map;

/** 权益物化结果；本类只校验时间参数，不能自行证明前缀完整。 */
public record CommittedPortfolioSnapshot(ProductLine productLine, long asOfEpochMillis,
                                         long sourceTimestamp, Map<Long, UserEquity> users) {
    public CommittedPortfolioSnapshot {
        if (productLine == null || asOfEpochMillis <= 0 || sourceTimestamp < 0 || sourceTimestamp > asOfEpochMillis)
            throw new IllegalArgumentException("invalid committed portfolio time");
        users = users == null ? Map.of() : Map.copyOf(users);
    }

    /** 一个用户的完整性标记及单资产金额文本。Map 固定不可修改，完整性为 false 时可能含余额或部分持仓值，不能据此发布完整净权益。 */
    public record UserEquity(boolean complete, Map<String, String> netAssetUnits) {
        /** complete 为 false 时这些值不能解释为完整净权益。 */
        public UserEquity { netAssetUnits = netAssetUnits == null ? Map.of() : Map.copyOf(netAssetUnits); }
    }
}
