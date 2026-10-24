package com.surprising.aeron.protocol;

/** 算法单新建意图与生命周期增量；账户归属由命令头提供。 */
public sealed interface CoreAlgoOrderCommand {
    int TWAP_TYPE_CODE = 0;
    int ICEBERG_TYPE_CODE = 1;
    long algoOrderId();

    record Create(long algoOrderId, String clientAlgoOrderId, String instrumentId, int algoTypeCode,
                  CoreOrderSide side, long priceTicks, long quantitySteps, long childQuantitySteps,
                  long intervalSeconds, long durationSeconds, CoreMarginMode marginMode,
                  CorePositionSide positionSide, boolean reduceOnly,
                  CoreTimeInForce timeInForce, long startAtEpochMillis) implements CoreAlgoOrderCommand {
        public Create {
            if (algoTypeCode == TWAP_TYPE_CODE) timeInForce = CoreTimeInForce.IOC;
            if (algoTypeCode == ICEBERG_TYPE_CODE) intervalSeconds = 0;
            clientAlgoOrderId = clientAlgoOrderId == null ? "" : clientAlgoOrderId;
            if (algoOrderId <= 0 || instrumentId == null || instrumentId.isBlank() || algoTypeCode < 0
                    || side == null || quantitySteps <= 0 || childQuantitySteps <= 0
                    || algoTypeCode != ICEBERG_TYPE_CODE && intervalSeconds <= 0
                    || durationSeconds <= 0 || marginMode == null || positionSide == null
                    || timeInForce == null || startAtEpochMillis < 0) {
                throw new IllegalArgumentException("invalid algo order creation");
            }
        }
    }

    record Update(long algoOrderId, long expectedRevision, int statusCode, long currentOrderId,
                  String rejectReason, long nextSliceAtEpochMillis,
                  long updatedAtEpochMillis, long appendedChildOrderId) implements CoreAlgoOrderCommand {
        public Update {
            rejectReason = rejectReason == null ? "" : rejectReason;
            if (algoOrderId <= 0 || expectedRevision < 1 || statusCode < 0 || currentOrderId < 0
                    || nextSliceAtEpochMillis < 0 || updatedAtEpochMillis <= 0
                    || appendedChildOrderId < 0) {
                throw new IllegalArgumentException("invalid algo order update");
            }
        }
    }
}
