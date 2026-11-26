package com.surprising.aeron.protocol.export;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;

/** 一次已提交命令尝试的最终导出收据，含可空下单身份及有界批撤逐项结果。 */
public record CommittedCommandResult(ProductLine product, long userId, UUID commandId,
                                     CoreMessageType commandType, String clientOrderId, String instrumentId,
                                     ResponseStatus status, CoreResultCode code, long appliedCommandCount,
                                     long clusterPosition, long occurredAt, List<CancelBatchItem> cancelResults) {
    /** 命令必须为 COMMAND，收据只能是已应用或拒绝，不能仍为 MATCHING_PENDING，身份及日志元数据必须有效。 */
    public CommittedCommandResult {
        cancelResults = cancelResults == null ? List.of() : List.copyOf(cancelResults);
        if (cancelResults.size() > 50 || (!cancelResults.isEmpty() && commandType != CoreMessageType.CANCEL_ORDER_BATCH))
            throw new IllegalArgumentException("invalid committed cancellation items");
        for (int i = 0; i < cancelResults.size(); i++)
            if (cancelResults.get(i).index() != i) throw new IllegalArgumentException("noncontiguous cancellation items");
        if (product == null || userId <= 0 || commandId == null || commandType == null
                || commandType.kind() != WireMessageKind.COMMAND
                || (status != ResponseStatus.APPLIED && status != ResponseStatus.REJECTED) || code == null
                || code == CoreResultCode.MATCHING_PENDING || appliedCommandCount < 0
                || clusterPosition <= 0 || occurredAt <= 0)
            throw new IllegalArgumentException("invalid committed command result");
    }

    /** 单个批撤请求的确定性结果，只有请求下标、订单身份、成功标记与结果码，不复制完整订单。 */
    public record CancelBatchItem(int index, long orderId, boolean success, String code) {
        /** 成功当且仅当码为 NONE，不允许保留 MATCHING_PENDING。 */
        public CancelBatchItem {
            if (index < 0 || orderId <= 0 || code == null || code.isBlank()
                    || CoreResultCode.valueOf(code) == CoreResultCode.MATCHING_PENDING
                    || success != (CoreResultCode.valueOf(code) == CoreResultCode.NONE))
                throw new IllegalArgumentException("invalid cancellation result item");
        }
    }
}
