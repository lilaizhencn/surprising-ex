package com.surprising.instrument.provider.model;

import com.surprising.instrument.api.model.InstrumentQueryResponse;
import java.util.Map;

/** Service聚合的可见合约及其真实行情；HTTP字段展平留在Controller。 */
public record InstrumentCatalog(InstrumentQueryResponse instruments, Map<Integer, MarketSummaryRows.Summary> summaries) {}
