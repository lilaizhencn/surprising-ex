package com.surprising.aeron.service.trigger;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreTriggerOrderState;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CorePositionMode;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderStatus;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.service.trigger.command.TriggerOrderCommands;

/** 条件单在 TradingAccountRuntime 中的生命周期和准入校验业务操作。 */
public final class TriggerOrderOperations {

    private final TradingAccountRuntime runtime;

    public TriggerOrderOperations(TradingAccountRuntime runtime) {
        this.runtime = runtime;
    }

    public void upsertTriggerOrder(long userId,
                                   CoreTriggerOrderStateView view, int symbolId,
                                   long positionKey, boolean instrumentSettled) {
        runtime.requireRevisionCapacity();
        CoreTriggerOrderState trigger = prepareTriggerPlacement(userId, view, symbolId, positionKey, instrumentSettled);
        runtime.putTriggerOrder(trigger);
        runtime.incrementCommandRevision();
    }

    /** 先校验止盈和止损两腿关系，再分别完成登记校验，并预检两次版本递增容量。 */
    public void placeOcoPair(long userId,
                             CoreTriggerOrderStateView takeProfit, CoreTriggerOrderStateView stopLoss,
                             int symbolId, long positionKey, boolean instrumentSettled) {
        TriggerOrderCommands.validateOcoPair(takeProfit, stopLoss);
        var first = prepareTriggerPlacement(userId, takeProfit, symbolId, positionKey, instrumentSettled);
        var second = prepareTriggerPlacement(userId, stopLoss, symbolId, positionKey, instrumentSettled);
        Math.addExact(runtime.revision(), 2);
        runtime.putTriggerOrder(first);
        runtime.putTriggerOrder(second);
        runtime.incrementCommandRevision();
        runtime.incrementCommandRevision();
    }

    /** 核对归属、客户标识、币对存在和未结算、普通编号与客户编号不重复，再检查持仓容量。 */
    public CoreTriggerOrderState prepareTriggerPlacement(long userId,
                                                        CoreTriggerOrderStateView view, int symbolId,
                                                        long positionKey, boolean instrumentSettled) {
        if (view == null || userId <= 0 || symbolId < 0 || positionKey < 0) {
            throw new IllegalArgumentException("invalid prepared runtime trigger order update");
        }
        runtime.assertOwner();
        if (view.userId() != userId || view.productLine() != runtime.productLine()) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (view.clientTriggerOrderId().isBlank()) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "clientTriggerOrderId is required");
        }
        CoreInstrument instrument = runtime.instrument(view.instrumentId());
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "trigger order instrument does not exist");
        }
        if (instrumentSettled) {
            throw new CoreStateRejectedException("INSTRUMENT_SETTLED", "instrument is already settled");
        }
        if (runtime.triggerOrder(view.triggerOrderId()) != null) {
            throw new CoreStateRejectedException("DUPLICATE_TRIGGER_ORDER_ID", "trigger order already exists");
        }
        if (runtime.hasTriggerClient(userId, view.clientTriggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "clientTriggerOrderId already exists");
        }
        validateTriggerPlacement(userId, symbolId, positionKey, view);
        return CoreTriggerOrderState.from(view, instrument);
    }

    private void validateTriggerPlacement(long userId, int symbolId,
                                          long positionKey, CoreTriggerOrderStateView view) {
        UserAccount user = runtime.user(userId);
        if (user == null) throw new CoreStateRejectedException("USER_NOT_FOUND", "user does not exist");
        if (!runtime.productLine().isDerivative()) return;
        if (user.positionMode() == CorePositionMode.ONE_WAY && view.positionSide().hedgeSide()
                || user.positionMode() == CorePositionMode.HEDGE && !view.positionSide().hedgeSide()) {
            throw new CoreStateRejectedException("POSITION_MODE_MISMATCH",
                    "trigger position side does not match user position mode");
        }
        Position position = runtime.position(positionKey);
        if (position == null || position.signedQuantitySteps() == 0) {
            throw new CoreStateRejectedException("TRIGGER_POSITION_REQUIRED",
                    "trigger order requires an open position");
        }
        if (position.marginMode() != view.marginMode()) {
            throw new CoreStateRejectedException("POSITION_MARGIN_ADJUSTMENT_INVALID",
                    "trigger margin mode does not match position");
        }
        CoreOrderSide closeSide = position.signedQuantitySteps() > 0 ? CoreOrderSide.SELL : CoreOrderSide.BUY;
        if (view.side() != closeSide) {
            throw new CoreStateRejectedException("TRIGGER_SIDE_NOT_REDUCING",
                    "trigger order side must reduce the current position");
        }
        long openReduceOnly = runtime.openReduceOnlyQuantity(
                userId, symbolId, view.positionSide(), closeSide, view.marginMode());
        long projectedTriggerCapacity = runtime.projectedTriggerCapacity(userId, view);
        if (Math.addExact(openReduceOnly, projectedTriggerCapacity)
                > Math.absExact(position.signedQuantitySteps())) {
            throw new CoreStateRejectedException("TRIGGER_CLOSE_CAPACITY_EXCEEDED",
                    "trigger order quantity exceeds available position");
        }
    }

    /** 核对用户归属，已不开放的条件单返回 false。 */
    public boolean cancelTriggerOrder(long userId, long triggerOrderId) {
        requireTriggerInput(userId, triggerOrderId);
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (current.userId() != userId) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_OWNER_MISMATCH", "trigger order owner mismatch");
        }
        if (!current.status().open()) return false;
        updateTriggerOrder(current, CoreTriggerOrderStatus.CANCELED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), current.updatedAtEpochMillis());
        return true;
    }

    /** 只取消仍为 PENDING 的条件单并写回后状态，命令级版本发布由原提交事件负责，本辅助入口不重复递增命令版本。 */
    public boolean cancelPendingTriggerOrderForCommit(long triggerId) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        runtime.putTriggerOrder(current.toPendingCancellation());
        return true;
    }

    /** 仅允许 PENDING 转为 TRIGGERING，记录本轮触发序号和价格。 */
    public boolean claimTriggerOrder(long triggerOrderId, long triggerSequence,
                                    long triggeredPriceTicks, long triggeredAtEpochMillis) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING) return false;
        updateTriggerOrder(current, CoreTriggerOrderStatus.TRIGGERING, 0, triggerSequence,
                triggeredPriceTicks, current.rejectReason(), triggeredAtEpochMillis);
        return true;
    }

    /** 仅允许已认领的 TRIGGERING 记录按结果转为 TRIGGERED 或 TRIGGER_FAILED，写子单编号及原因。 */
    public boolean completeTriggerOrder(long triggerOrderId, boolean success,
                                       long placedOrderId, String rejectReason, long completedAtEpochMillis) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING) return false;
        updateTriggerOrder(current, success ? CoreTriggerOrderStatus.TRIGGERED
                        : CoreTriggerOrderStatus.TRIGGER_FAILED, placedOrderId, current.triggerSequence(),
                current.triggeredPriceTicks(), rejectReason, completedAtEpochMillis);
        return true;
    }

    /** 只更新仍开放的移动止损，采用调用方算出的高低价格水位和激活时间，保留原执行信息并递增版本。 */
    public boolean updateTrailingTriggerOrder(long triggerOrderId,
                                             long highestPriceTicks, long lowestPriceTicks,
                                             long activatedAtEpochMillis) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (!current.status().open() || current.triggerType() != CoreTriggerOrderType.TRAILING_STOP) return false;
        runtime.putTriggerOrder(new CoreTriggerOrderState(current.triggerOrderId(),
                current.userId(), current.clientTriggerOrderId(), current.ocoGroupId(),
                current.instrument(), current.side(),
                current.triggerType(), current.triggerCondition(), current.triggerPriceTicks(),
                current.activationPriceTicks(), current.callbackRatePpm(), highestPriceTicks, lowestPriceTicks,
                activatedAtEpochMillis, current.orderType(), current.timeInForce(), current.priceTicks(),
                current.quantitySteps(), current.marginMode(), current.positionSide(), current.status(),
                current.placedOrderId(), current.triggerSequence(), current.triggeredPriceTicks(), current.rejectReason(),
                current.expiresAtEpochMillis(), current.triggeredAtEpochMillis(),
                current.createdAtEpochMillis(), Math.max(current.updatedAtEpochMillis(), activatedAtEpochMillis),
                Math.incrementExact(current.revision()), current.priceSource()));
        runtime.incrementCommandRevision();
        return true;
    }

    /** 只有待触发、设置了到期时间且已到期的记录才转为 EXPIRED，其他情况返回 false。 */
    public boolean expireTriggerOrder(long triggerOrderId, long expiredAtEpochMillis) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.PENDING || current.expiresAtEpochMillis() == 0
                || current.expiresAtEpochMillis() > expiredAtEpochMillis) return false;
        updateTriggerOrder(current, CoreTriggerOrderStatus.EXPIRED, 0, current.triggerSequence(),
                current.triggeredPriceTicks(), current.rejectReason(), expiredAtEpochMillis);
        return true;
    }

    /** 仅把更新时间不晚于给定界限的 TRIGGERING 记录恢复为 PENDING，清除子单关联及触发序号价格。 */
    public boolean retryTriggerOrder(long triggerOrderId,
                                    long staleBeforeEpochMillis, long retryAtEpochMillis) {
        CoreTriggerOrderState current = requireTriggerOrder(triggerOrderId);
        if (current.status() != CoreTriggerOrderStatus.TRIGGERING
                || current.updatedAtEpochMillis() > staleBeforeEpochMillis) return false;
        updateTriggerOrder(current, CoreTriggerOrderStatus.PENDING, 0, 0, 0,
                current.rejectReason(), retryAtEpochMillis);
        return true;
    }

    private void updateTriggerOrder(CoreTriggerOrderState current,
                                    CoreTriggerOrderStatus status, long placedOrderId, long triggerSequence,
                                    long triggeredPriceTicks, String rejectReason, long updatedAt) {
        runtime.requireRevisionCapacity();
        runtime.putTriggerOrder(current.transition(status, placedOrderId, triggerSequence,
                triggeredPriceTicks, rejectReason, updatedAt));
        runtime.incrementCommandRevision();
    }

    private CoreTriggerOrderState requireTriggerOrder(long triggerOrderId) {
        if (triggerOrderId <= 0) throw new IllegalArgumentException("invalid runtime trigger order");
        runtime.assertOwner();
        CoreTriggerOrderState current = runtime.triggerOrder(triggerOrderId);
        if (current == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        return current;
    }

    private static void requireTriggerInput(long userId, long triggerOrderId) {
        if (userId <= 0 || triggerOrderId <= 0) {
            throw new IllegalArgumentException("invalid runtime trigger order");
        }
    }
}
