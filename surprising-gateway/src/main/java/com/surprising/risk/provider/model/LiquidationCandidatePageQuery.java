package com.surprising.risk.provider.model;

import com.surprising.risk.api.model.LiquidationCandidateStatus;

/** HTTP绑定的候选查询；Core投影按清算ID续页，不携带原始游标或排序文本。 */
public record LiquidationCandidatePageQuery(LiquidationCandidateStatus status, int limit,
                                             Long afterId, boolean descending) {
}
