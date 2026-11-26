package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreAlgoOrderState;
import com.surprising.aeron.model.CoreCancelAllAfterKey;
import com.surprising.aeron.model.CoreCancelAllAfterState;
import com.surprising.aeron.protocol.CoreAlgoOrderCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterCommand;
import com.surprising.aeron.protocol.CoreCancelAllAfterStatus;
import com.surprising.aeron.service.core.identity.IdIndex;
import java.util.ArrayList;
import java.util.List;

/** Algo-order and cancel-all-after timer state transitions executed under AccountBook owner access. */
final class AlgoOrderOperations {

    private final AccountBook book;

    AlgoOrderOperations(AccountBook book) {
        this.book = book;
    }

    void updateCancelAllAfter(long userId, CoreCancelAllAfterCommand command) {
        if (command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime cancel-all-after update");
        }
        book.assertOwner();
        CoreCancelAllAfterKey key = new CoreCancelAllAfterKey(userId, command.symbolScope());
        CoreCancelAllAfterState current = book.cancelAllAfterTimer(key);
        CoreCancelAllAfterState next;
        switch (command.action()) {
            case SET -> {
                CoreCancelAllAfterStatus status = command.countdownMillis() == 0
                        ? CoreCancelAllAfterStatus.DISABLED : CoreCancelAllAfterStatus.ACTIVE;
                long triggerAt;
                try {
                    triggerAt = command.countdownMillis() == 0 ? 0
                            : Math.addExact(command.updatedAtEpochMillis(), command.countdownMillis());
                } catch (ArithmeticException overflow) {
                    throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_TRIGGER", "timer deadline overflow");
                }
                next = new CoreCancelAllAfterState(userId, command.symbolScope(), status,
                        triggerAt,
                        command.updatedAtEpochMillis(),
                        current == null ? 1 : Math.incrementExact(current.revision()));
            }
            case CLAIM -> {
                requireCancelAllAfterRevision(current, command);
                if (!current.claimableAt(command.updatedAtEpochMillis())) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_DUE", "timer is not due");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.TRIGGERING, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            case COMPLETE -> {
                requireCancelAllAfterRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.TRIGGERED, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            case RETRY -> {
                requireCancelAllAfterRevision(current, command);
                if (current.status() != CoreCancelAllAfterStatus.TRIGGERING) {
                    throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_CLAIMED", "timer is not claimed");
                }
                next = new CoreCancelAllAfterState(userId, current.symbolScope(),
                        CoreCancelAllAfterStatus.ACTIVE, current.triggerAtEpochMillis(),
                        command.updatedAtEpochMillis(),
                        Math.incrementExact(current.revision()));
            }
            default -> throw new CoreStateRejectedException("INVALID_CANCEL_ALL_AFTER_ACTION", "unsupported action");
        }
        book.requireRevisionCapacity();
        book.putCancelAllAfterTimer(key, next);
        book.incrementCommandRevision();
    }

    private static void requireCancelAllAfterRevision(CoreCancelAllAfterState current, CoreCancelAllAfterCommand command) {
        if (current == null) {
            throw new CoreStateRejectedException("CANCEL_ALL_AFTER_NOT_FOUND", "timer not found");
        }
        if (command.expectedRevision() != current.revision()) {
            throw new CoreStateRejectedException("STALE_CANCEL_ALL_AFTER_REVISION", "timer revision is stale");
        }
    }

    void upsertAlgoOrder(IdIndex identities, long userId, CoreAlgoOrderCommand command, long clusterTimestamp) {
        if (identities == null || command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid runtime algo order update");
        }
        var current = book.algoOrder(command.algoOrderId());
        int symbolId = -1;
        if (command instanceof CoreAlgoOrderCommand.Create create) symbolId = identities.symbolId(create.instrumentId());
        else if (current != null) symbolId = identities.symbolId(current.instrumentId());
        upsertAlgoOrder(userId, command, symbolId, clusterTimestamp);
    }

    void upsertAlgoOrder(long userId, CoreAlgoOrderCommand command, int symbolId, long clusterTimestamp) {
        if (command == null || userId <= 0) {
            throw new IllegalArgumentException("invalid prepared algo update");
        }
        book.assertOwner();
        CoreAlgoOrderState current = book.algoOrder(command.algoOrderId());
        if (current != null && current.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        CoreAlgoOrderState next;
        switch (command) {
            case CoreAlgoOrderCommand.Create create -> {
                if (current != null) throw new CoreStateRejectedException("INVALID_ALGO_ORDER_CREATE",
                        "algo order already exists");
                if (symbolId < 0 || clusterTimestamp <= 0) throw new IllegalArgumentException("invalid algo creation context");
                if (create.clientAlgoOrderId().isBlank()) {
                    throw new CoreStateRejectedException("INVALID_COMMAND", "clientAlgoOrderId is required");
                }
                if (book.hasAlgoClient(userId, create.clientAlgoOrderId())) {
                    throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                            "clientAlgoOrderId already exists");
                }
                long start = Math.max(create.startAtEpochMillis(), clusterTimestamp);
                next = new CoreAlgoOrderState(create.algoOrderId(), userId, create.clientAlgoOrderId(),
                        create.instrumentId(), create.algoTypeCode(), create.side(), create.priceTicks(),
                        create.quantitySteps(), create.childQuantitySteps(), create.intervalSeconds(),
                        create.durationSeconds(),
                        create.marginMode(), create.positionSide(), create.reduceOnly(),
                        create.timeInForce(),
                        0, 0, "", start, start, clusterTimestamp, clusterTimestamp, 1, List.of());
            }
            case CoreAlgoOrderCommand.Update update -> {
                if (current == null) throw new CoreStateRejectedException("ENTITY_NOT_FOUND", "algo order is missing");
                if (symbolId < 0) throw new IllegalArgumentException("invalid algo update symbol");
                if (current.terminal() || current.statusCode() == 2 && update.statusCode() < 2) {
                    throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "terminal or cancel-requested algo cannot resume execution");
                }
                if (update.expectedRevision() < current.revision()) {
                    throw new CoreStateRejectedException("STALE_ALGO_ORDER_REVISION", "algo order revision is stale");
                }
                if (update.expectedRevision() != current.revision()) {
                    throw new CoreStateRejectedException("INVALID_ALGO_ORDER_REVISION",
                            "algo order revision is not monotonic");
                }
                List<Long> children = current.childOrderIds();
                long pending = current.pendingClosedChildId();
                long appended = update.appendedChildOrderId();
                if (appended != 0) {
                    var child = book.order(appended);
                    if ((child == null && pending != appended)
                            || (child != null && (child.userId() != userId || child.symbolId() != symbolId
                            || !("algo-" + current.algoOrderId() + "-" + children.size()).equals(child.clientOrderId())))) {
                        throw new CoreStateRejectedException("INVALID_ALGO_CHILD", "algo child order is not authoritative");
                    }
                    var appendedChildren = new ArrayList<>(children);
                    appendedChildren.add(appended);
                    children = appendedChildren;
                    if (pending == appended) pending = 0;
                }
                next = new CoreAlgoOrderState(current.algoOrderId(), current.userId(), current.clientAlgoOrderId(),
                        current.instrumentId(), current.algoTypeCode(), current.side(), current.priceTicks(),
                        current.quantitySteps(), current.childQuantitySteps(), current.intervalSeconds(),
                        current.durationSeconds(),
                        current.marginMode(), current.positionSide(), current.reduceOnly(),
                        current.timeInForce(),
                        update.statusCode(), update.currentOrderId(), update.rejectReason(), current.startAtEpochMillis(),
                        update.nextSliceAtEpochMillis(), current.createdAtEpochMillis(),
                        update.updatedAtEpochMillis(), Math.incrementExact(current.revision()), children,
                        current.retiredExecutedQuantitySteps(), pending);
            }
        }
        book.requireRevisionCapacity();
        book.putAlgoOrder(next);
        book.incrementCommandRevision();
    }
}
