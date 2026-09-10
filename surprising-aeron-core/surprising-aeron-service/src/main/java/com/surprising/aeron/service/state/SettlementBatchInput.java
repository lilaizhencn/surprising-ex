package com.surprising.aeron.service.state;

import com.surprising.aeron.service.matching.CoreMatchingResult;

/** Owner 同步读取的批量结算输入；事件仅持有构建后的计划，不保留调用方可复用列表。 */
public interface SettlementBatchInput {
    int settlementCount();
    long settlementOrderId(int index);
    long settlementLaneMask(int index);
    CoreMatchingResult settlementResult(int index);
}
