package com.surprising.adl.provider.repository;

import com.surprising.adl.provider.model.DeficitRow;
import com.surprising.product.api.ProductLine;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * ADL 缺口读取契约。具体实现分别绑定一张兼容缺口表，产品线选择由上层聚合逻辑完成。
 */
public interface AdlDeficitRepository {

    List<DeficitRow> claimResidual(String accountType, int batchSize, Duration minAge);

    Map<Long, Long> remainingByUsers(ProductLine productLine, String asset, List<Long> userIds);
}
