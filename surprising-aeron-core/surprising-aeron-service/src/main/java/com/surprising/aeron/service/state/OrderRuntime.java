package com.surprising.aeron.service.state;

import com.surprising.aeron.service.state.model.CoreOrderStatus;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreOrderType;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.CoreTimeInForce;
import com.surprising.product.api.ProductLine;
import java.util.Objects;
import java.util.UUID;

/**
 * Runtime order state owned by one Account Lane.
 *
 * <p>The Lane mutates its execution fields in place. Cross-thread publication captures the
 * mutable fields into primitive event storage and updates a distinct Owner-owned mirror, so a
 * later Lane command can never change an already committed value. Explicit snapshot/rollback
 * boundaries still use {@link #snapshot()}.</p>
 */
public final class OrderRuntime {
    private final long orderId;
    private final ProductLine productLine;
    private final long userId;
    private final int symbolId;
    private final CoreInstrument instrument;
    private final CoreOrderSide side;
    private final long priceTicks;
    private final long matchingPriceTicks;
    private final long quantitySteps;
    private long executedQuantitySteps;
    private long remainingQuantitySteps;
    private final boolean reduceOnly;
    private final CoreMarginMode marginMode;
    private final CorePositionSide positionSide;
    private final CoreOrderType orderType;
    private final CoreTimeInForce timeInForce;
    private final boolean postOnly;
    private final String clientOrderId;
    private final UUID commandId;
    private final long makerFeeRatePpm;
    private final long takerFeeRatePpm;
    private long cumulativeFeeUnits;
    private long createdAtEpochMillis;
    private long updatedAtEpochMillis;
    private long clusterPosition;
    private CoreOrderStatus status;
    private long revision;
    private boolean mutable;

    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType,
                        CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                        UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long cumulativeFeeUnits, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        if (orderId <= 0 || productLine == null || userId <= 0 || symbolId < 0 || instrument == null
                || side == null || priceTicks < 0 || matchingPriceTicks < 0
                || quantitySteps <= 0 || executedQuantitySteps < 0 || remainingQuantitySteps < 0
                || Math.addExact(executedQuantitySteps, remainingQuantitySteps) != quantitySteps
                || marginMode == null || positionSide == null || orderType == null || timeInForce == null
                || clientOrderId == null || clientOrderId.length() > 64 || commandId == null
                || createdAtEpochMillis < 0 || updatedAtEpochMillis < createdAtEpochMillis || clusterPosition < 0
                || postOnly && (orderType != CoreOrderType.LIMIT || timeInForce != CoreTimeInForce.GTX)
                || status == null || revision <= 0
                || status == CoreOrderStatus.OPEN && remainingQuantitySteps == 0) {
            throw new IllegalArgumentException("invalid runtime order");
        }
        this.orderId = orderId;
        this.productLine = productLine;
        this.userId = userId;
        this.symbolId = symbolId;
        this.instrument = instrument;
        this.side = side;
        this.priceTicks = priceTicks;
        this.matchingPriceTicks = matchingPriceTicks;
        this.quantitySteps = quantitySteps;
        this.executedQuantitySteps = executedQuantitySteps;
        this.remainingQuantitySteps = remainingQuantitySteps;
        this.reduceOnly = reduceOnly;
        this.marginMode = marginMode;
        this.positionSide = positionSide;
        this.orderType = orderType;
        this.timeInForce = timeInForce;
        this.postOnly = postOnly;
        this.clientOrderId = clientOrderId;
        this.commandId = commandId;
        this.makerFeeRatePpm = makerFeeRatePpm;
        this.takerFeeRatePpm = takerFeeRatePpm;
        this.cumulativeFeeUnits = cumulativeFeeUnits;
        this.createdAtEpochMillis = createdAtEpochMillis;
        this.updatedAtEpochMillis = updatedAtEpochMillis;
        this.clusterPosition = clusterPosition;
        this.status = status;
        this.revision = revision;
        this.mutable = true;
    }

    private OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                         CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                         long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                         long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                         CorePositionSide positionSide, CoreOrderType orderType,
                         CoreTimeInForce timeInForce, boolean postOnly, String clientOrderId,
                         UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                         long cumulativeFeeUnits, long createdAtEpochMillis, long updatedAtEpochMillis,
                         long clusterPosition, CoreOrderStatus status, long revision, boolean mutable) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
        // The delegating constructor initializes all value fields and defaults to a mutable
        // Lane value.  Only snapshot() uses false; this flag is publication metadata.
        this.mutable = mutable;
    }

    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long quantitySteps, long executedQuantitySteps, long remainingQuantitySteps,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, boolean postOnly,
                        String clientOrderId, UUID commandId, long makerFeeRatePpm, long takerFeeRatePpm,
                        long createdAtEpochMillis, long updatedAtEpochMillis, long clusterPosition,
                        CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, 0, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, status, revision);
    }

    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        long matchingPriceTicks, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean reduceOnly, CoreMarginMode marginMode,
                        CorePositionSide positionSide, CoreOrderType orderType, CoreTimeInForce timeInForce,
                        boolean postOnly, String clientOrderId, UUID commandId, long makerFeeRatePpm,
                        long takerFeeRatePpm, long createdAtEpochMillis, long updatedAtEpochMillis,
                        long clusterPosition, CoreOrderStatus status, long revision) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, 0, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
    }

    public OrderRuntime(long orderId, long userId, int symbolId, CoreInstrument instrument,
                        CoreOrderSide side, long priceTicks, boolean reduceOnly,
                        CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce,
                        long makerFeeRatePpm, long takerFeeRatePpm, long quantitySteps,
                        long executedQuantitySteps, long remainingQuantitySteps, boolean canceled) {
        this(orderId, ProductLine.LINEAR_PERPETUAL, userId, symbolId, instrument, side,
                priceTicks, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                makerFeeRatePpm, takerFeeRatePpm, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, canceled);
    }

    public OrderRuntime(long orderId, ProductLine productLine, long userId, int symbolId,
                        CoreInstrument instrument, CoreOrderSide side, long priceTicks,
                        boolean reduceOnly, CoreMarginMode marginMode, CorePositionSide positionSide,
                        CoreOrderType orderType, CoreTimeInForce timeInForce, long makerFeeRatePpm,
                        long takerFeeRatePpm, long quantitySteps, long executedQuantitySteps,
                        long remainingQuantitySteps, boolean canceled) {
        this(orderId, productLine, userId, symbolId, instrument, side, priceTicks, priceTicks,
                quantitySteps, executedQuantitySteps, remainingQuantitySteps, reduceOnly, marginMode,
                positionSide, orderType, timeInForce, false, "", new UUID(0, orderId),
                makerFeeRatePpm, takerFeeRatePpm, 0, 0, 0, 0,
                canceled ? CoreOrderStatus.CANCELED : CoreOrderStatus.OPEN, 1);
    }

    public long orderId() { return orderId; }
    public ProductLine productLine() { return productLine; }
    public long userId() { return userId; }
    public int symbolId() { return symbolId; }
    public CoreInstrument instrument() { return instrument; }
    public CoreOrderSide side() { return side; }
    public long priceTicks() { return priceTicks; }
    public long matchingPriceTicks() { return matchingPriceTicks; }
    public long quantitySteps() { return quantitySteps; }
    public long executedQuantitySteps() { return executedQuantitySteps; }
    public long remainingQuantitySteps() { return remainingQuantitySteps; }
    public boolean reduceOnly() { return reduceOnly; }
    public CoreMarginMode marginMode() { return marginMode; }
    public CorePositionSide positionSide() { return positionSide; }
    public CoreOrderType orderType() { return orderType; }
    public CoreTimeInForce timeInForce() { return timeInForce; }
    public boolean postOnly() { return postOnly; }
    public String clientOrderId() { return clientOrderId; }
    public UUID commandId() { return commandId; }
    public long makerFeeRatePpm() { return makerFeeRatePpm; }
    public long takerFeeRatePpm() { return takerFeeRatePpm; }
    public long cumulativeFeeUnits() { return cumulativeFeeUnits; }
    public long createdAtEpochMillis() { return createdAtEpochMillis; }
    public long updatedAtEpochMillis() { return updatedAtEpochMillis; }
    public long clusterPosition() { return clusterPosition; }
    public CoreOrderStatus status() { return status; }
    public long revision() { return revision; }

    public boolean canceled() { return status.terminal(); }

    /** Immutable value copy for Owner publication, snapshot and rollback boundaries. */
    public OrderRuntime snapshot() {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis, clusterPosition,
                status, revision, false);
    }

    OrderRuntime laneValue() { return mutable ? this : mutableCopy(); }

    OrderRuntime copyForLane() { return mutableCopy(); }

    private OrderRuntime mutableCopy() {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, status, revision);
    }

    OrderRuntime publicationValue() { return mutable ? snapshot() : this; }

    void applyPublishedStateInPlace(OrderRuntime source, long executed, long remaining,
                                    long cumulativeFee, long createdAt, long updatedAt,
                                    long position, CoreOrderStatus publishedStatus,
                                    long publishedRevision) {
        if (source == null || orderId != source.orderId || instrument != source.instrument
                || executed < 0 || remaining < 0 || Math.addExact(executed, remaining) != quantitySteps
                || createdAt < 0 || updatedAt < createdAt || position < 0
                || publishedStatus == null || publishedRevision <= 0) {
            throw new IllegalStateException("invalid published order state");
        }
        executedQuantitySteps = executed;
        remainingQuantitySteps = remaining;
        cumulativeFeeUnits = cumulativeFee;
        createdAtEpochMillis = createdAt;
        updatedAtEpochMillis = updatedAt;
        clusterPosition = position;
        status = publishedStatus;
        revision = publishedRevision;
    }

    /** Lane-only mutation; all validation is performed before changing any field. */
    void applyFillInPlace(long executed, long remaining, long feeUnits,
                          CoreOrderStatus nextStatus, long nextRevision,
                          long commitTimestamp, long commitPosition) {
        if (executed < 0 || remaining < 0 || Math.addExact(executed, remaining) != quantitySteps
                || nextStatus == null || nextRevision <= 0 || commitTimestamp < -1 || commitPosition < -1
                || commitTimestamp >= 0 && commitPosition < 0) {
            throw new IllegalArgumentException("invalid runtime order execution");
        }
        executedQuantitySteps = executed;
        remainingQuantitySteps = remaining;
        cumulativeFeeUnits = Math.addExact(cumulativeFeeUnits, feeUnits);
        if (commitTimestamp >= 0) {
            createdAtEpochMillis = commitTimestamp;
            updatedAtEpochMillis = commitTimestamp;
            clusterPosition = commitPosition;
        }
        status = nextStatus;
        revision = nextRevision;
    }

    void applyStatusInPlace(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        applyFillInPlace(executedQuantitySteps, remainingQuantitySteps, 0,
                nextStatus, nextRevision, commitTimestamp, commitPosition);
    }

    void applyCommitMetadataInPlace(long timestamp, long position) {
        if (timestamp < 0 || position < 0) throw new IllegalArgumentException("invalid runtime order metadata");
        createdAtEpochMillis = timestamp;
        updatedAtEpochMillis = timestamp;
        clusterPosition = position;
    }

    public OrderRuntime withExecution(long executed, long remaining, CoreOrderStatus nextStatus, long nextRevision) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side, priceTicks,
                matchingPriceTicks, quantitySteps, executed, remaining, reduceOnly, marginMode, positionSide,
                orderType, timeInForce, postOnly, clientOrderId, commandId, makerFeeRatePpm,
                takerFeeRatePpm, cumulativeFeeUnits, createdAtEpochMillis, updatedAtEpochMillis,
                clusterPosition, nextStatus, nextRevision);
    }

    public OrderRuntime withFill(long executed, long remaining, long feeUnits,
                                 CoreOrderStatus nextStatus, long nextRevision) {
        return withFill(executed, remaining, feeUnits, nextStatus, nextRevision, -1, -1);
    }

    OrderRuntime withFill(long executed, long remaining, long feeUnits,
                          CoreOrderStatus nextStatus, long nextRevision,
                          long commitTimestamp, long commitPosition) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executed, remaining, reduceOnly,
                marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId, commandId,
                makerFeeRatePpm, takerFeeRatePpm, Math.addExact(cumulativeFeeUnits, feeUnits),
                commitTimestamp < 0 ? createdAtEpochMillis : commitTimestamp,
                commitTimestamp < 0 ? updatedAtEpochMillis : commitTimestamp,
                commitTimestamp < 0 ? clusterPosition : commitPosition, nextStatus, nextRevision);
    }

    public OrderRuntime withStatus(CoreOrderStatus nextStatus, long nextRevision) {
        return withExecution(executedQuantitySteps, remainingQuantitySteps, nextStatus, nextRevision);
    }

    OrderRuntime withStatus(CoreOrderStatus nextStatus, long nextRevision,
                            long commitTimestamp, long commitPosition) {
        return withFill(executedQuantitySteps, remainingQuantitySteps, 0, nextStatus, nextRevision,
                commitTimestamp, commitPosition);
    }

    public OrderRuntime withCommitMetadata(long timestamp, long position) {
        return new OrderRuntime(orderId, productLine, userId, symbolId, instrument, side,
                priceTicks, matchingPriceTicks, quantitySteps, executedQuantitySteps,
                remainingQuantitySteps, reduceOnly, marginMode, positionSide, orderType, timeInForce,
                postOnly, clientOrderId, commandId, makerFeeRatePpm, takerFeeRatePpm,
                cumulativeFeeUnits, timestamp, timestamp, position, status, revision);
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof OrderRuntime value)) return false;
        return orderId == value.orderId && userId == value.userId && symbolId == value.symbolId
                && instrument == value.instrument && priceTicks == value.priceTicks
                && matchingPriceTicks == value.matchingPriceTicks && quantitySteps == value.quantitySteps
                && executedQuantitySteps == value.executedQuantitySteps
                && remainingQuantitySteps == value.remainingQuantitySteps && reduceOnly == value.reduceOnly
                && postOnly == value.postOnly && makerFeeRatePpm == value.makerFeeRatePpm
                && takerFeeRatePpm == value.takerFeeRatePpm && cumulativeFeeUnits == value.cumulativeFeeUnits
                && createdAtEpochMillis == value.createdAtEpochMillis && updatedAtEpochMillis == value.updatedAtEpochMillis
                && clusterPosition == value.clusterPosition && revision == value.revision
                && productLine == value.productLine && side == value.side && marginMode == value.marginMode
                && positionSide == value.positionSide && orderType == value.orderType && timeInForce == value.timeInForce
                && status == value.status && Objects.equals(clientOrderId, value.clientOrderId)
                && Objects.equals(commandId, value.commandId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(orderId, productLine, userId, symbolId, System.identityHashCode(instrument), side, priceTicks,
                matchingPriceTicks, quantitySteps, executedQuantitySteps, remainingQuantitySteps,
                reduceOnly, marginMode, positionSide, orderType, timeInForce, postOnly, clientOrderId,
                commandId, makerFeeRatePpm, takerFeeRatePpm, cumulativeFeeUnits, createdAtEpochMillis,
                updatedAtEpochMillis, clusterPosition, status, revision);
    }

    @Override
    public String toString() {
        return "OrderRuntime[orderId=" + orderId + ", productLine=" + productLine + ", userId=" + userId
                + ", symbolId=" + symbolId + ", instrument=" + instrument + ", side=" + side
                + ", priceTicks=" + priceTicks + ", matchingPriceTicks=" + matchingPriceTicks
                + ", quantitySteps=" + quantitySteps + ", executedQuantitySteps=" + executedQuantitySteps
                + ", remainingQuantitySteps=" + remainingQuantitySteps + ", status=" + status
                + ", revision=" + revision + "]";
    }
}
