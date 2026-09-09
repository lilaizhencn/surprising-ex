package com.surprising.aeron.service.execution;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RiskScanRuntime;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.TradingRuntimeState;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import static com.surprising.aeron.service.execution.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CoreOrderSide;

/** 触发单和算法单生命周期命令；各产品线使用所属运行时和订单规则。 */
final class TriggerOrderCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    final TradingCoreRuntime owner;

    TriggerOrderCommands(TradingCoreRuntime owner) { this.owner = owner; }

    void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        Integer symbolId = owner.identities.findSymbolId(command.symbol());
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState.riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = owner.triggerOrderIndex.maxPendingId(command.symbol());
        RuntimeCommandProcessor.replaceRiskScan(owner.runtimeState,
                scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    void evaluatePendingTriggerScan(String symbol, int maxWork) {
        Integer symbolId = owner.identities.findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState.riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return;
        long markPriceTicks = scan.triggerMarkPriceTicks();
        long triggeredAt = scan.triggerGeneratedAtEpochMillis();
        UUID commandId = UUID.nameUUIDFromBytes((owner.productLine.name() + ":MARK_PRICE:" + symbol + ":"
                + scan.priceSequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        int remaining = Math.min(maxWork, TradingCoreRuntime.DEFAULT_TRIGGER_SCAN_BATCH_SIZE);
        if (scan.triggerOcoOrderId() != 0) {
            var pendingTrigger = owner.runtimeState.triggerOrder(scan.triggerOcoOrderId());
            if (pendingTrigger == null
                    || pendingTrigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                scan = scan.withTriggerOcoProgress(0, 0);
                replaceRiskScan(scan);
            } else {
                TradingCoreRuntime.OcoCancellationPage oco = cancelOcoSiblings(pendingTrigger,
                        scan.triggerOcoCursor() == 0 ? Long.MAX_VALUE : scan.triggerOcoCursor(), remaining);
                remaining -= oco.workUnits();
                if (!oco.complete()) {
                    replaceRiskScan(scan.withTriggerOcoProgress(pendingTrigger.triggerOrderId(), oco.nextCursor()));
                    return;
                }
                scan = scan.withTriggerOcoProgress(0, 0);
                replaceRiskScan(scan);
            }
        }
        if (remaining <= 0) return;
        var page = owner.triggerOrderIndex.candidatesPage(symbol, markPriceTicks, scan.triggerPhase(),
                scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(), remaining);
        if (page.ids().isEmpty() && page.complete()) {
            replaceRiskScan(scan.withTriggerProgress(true, page.nextPhase(), page.nextPriceCursor(),
                    page.nextOrderCursor(), scan.triggerUpperId(), markPriceTicks, triggeredAt));
            return;
        }
        for (long triggerOrderId : page.ids()) {
            var trigger = owner.runtimeState.triggerOrder(triggerOrderId);
            if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                continue;
            }
            if (trigger.expiresAtEpochMillis() > 0 && triggeredAt > 0
                    && trigger.expiresAtEpochMillis() <= triggeredAt) {
                expireTriggerOrderRuntime(triggerOrderId, triggeredAt);
                continue;
            }
            boolean triggered;
            if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
                if (trigger.activationPriceTicks() > 0
                        && ((sell && markPriceTicks < trigger.activationPriceTicks())
                        || (!sell && markPriceTicks > trigger.activationPriceTicks()))) {
                    continue;
                }
                long highest = sell
                        ? Math.max(trigger.highestPriceTicks(), markPriceTicks)
                        : trigger.highestPriceTicks();
                long lowest = sell
                        ? trigger.lowestPriceTicks()
                        : trigger.lowestPriceTicks() == 0
                        ? markPriceTicks
                        : Math.min(trigger.lowestPriceTicks(), markPriceTicks);
                long activatedAt = trigger.activatedAtEpochMillis() == 0 ? triggeredAt
                        : trigger.activatedAtEpochMillis();
                if (highest != trigger.highestPriceTicks() || lowest != trigger.lowestPriceTicks()
                        || activatedAt != trigger.activatedAtEpochMillis()) {
                    updateTriggerTrailingRuntime(triggerOrderId, highest, lowest, activatedAt);
                    trigger = owner.runtimeState.triggerOrder(triggerOrderId);
                }
                long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
                long delta = trailingDelta(base, trigger.callbackRatePpm());
                long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
                triggered = trigger.activatedAtEpochMillis() > 0
                        && (sell ? markPriceTicks <= threshold : markPriceTicks >= threshold);
            } else {
                triggered = trigger.triggerCondition()
                        == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                        ? markPriceTicks >= trigger.triggerPriceTicks()
                        : markPriceTicks <= trigger.triggerPriceTicks();
            }
            if (!triggered) continue;
            if (remaining <= 0) {
                replaceRiskScan(scan.withTriggerOcoProgress(triggerOrderId, Long.MAX_VALUE));
                return;
            }
            TradingCoreRuntime.OcoCancellationPage oco = cancelOcoSiblings(trigger, Long.MAX_VALUE, remaining);
            remaining -= oco.workUnits();
            if (!oco.complete()) {
                replaceRiskScan(scan.withTriggerOcoProgress(triggerOrderId, oco.nextCursor()));
                return;
            }
            executeTriggerOrder(triggerOrderId, scan.priceSequence(), markPriceTicks, triggeredAt, commandId, false);
        }
        replaceRiskScan(scan.withTriggerProgress(page.complete(), page.nextPhase(), page.nextPriceCursor(),
                page.nextOrderCursor(), scan.triggerUpperId(), markPriceTicks, triggeredAt)
                .withTriggerOcoProgress(0, 0));
    }

    void replaceRiskScan(RiskScanRuntime scan) {
        RuntimeCommandProcessor.replaceRiskScan(owner.runtimeState, scan);
        owner.commits.requestCommitPublication();
    }

    static long trailingDelta(long base, long callbackRatePpm) {
        if (base <= 0 || callbackRatePpm <= 0) return 0;
        return Math.floorDiv(Math.multiplyExact(base, callbackRatePpm), 1_000_000L);
    }

    static boolean isTriggerConditionSatisfied(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long priceTicks) {
        if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
            boolean sell = trigger.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL;
            long base = sell ? trigger.highestPriceTicks() : trigger.lowestPriceTicks();
            if (base <= 0 || trigger.activatedAtEpochMillis() <= 0) return false;
            long delta = trailingDelta(base, trigger.callbackRatePpm());
            long threshold = sell ? Math.subtractExact(base, delta) : Math.addExact(base, delta);
            return sell ? priceTicks <= threshold : priceTicks >= threshold;
        }
        return trigger.triggerCondition() == com.surprising.aeron.protocol.CoreTriggerCondition.GREATER_OR_EQUAL
                ? priceTicks >= trigger.triggerPriceTicks() : priceTicks <= trigger.triggerPriceTicks();
    }

    TradingCoreRuntime.OcoCancellationPage cancelOcoSiblings(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long cursor, int limit) {
        if (limit <= 0) return new TradingCoreRuntime.OcoCancellationPage(false, cursor, 0);
        List<Long> page = new ArrayList<>(limit);
        boolean more = false;
        for (Long siblingId : owner.triggerOrderIndex.ocoSiblings(trigger).descendingSet()) {
            if (siblingId == null || siblingId == trigger.triggerOrderId() || siblingId >= cursor) continue;
            if (page.size() >= limit) {
                more = true;
                break;
            }
            page.add(siblingId);
        }
        long nextCursor = cursor;
        int work = 0;
        for (long siblingId : page) {
            nextCursor = siblingId;
            work++;
            var sibling = owner.runtimeState.triggerOrder(siblingId);
            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                cancelTriggerOrderRuntime(sibling.userId(), siblingId);
            }
        }
        return new TradingCoreRuntime.OcoCancellationPage(!more, nextCursor, work);
    }

    void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        executeTriggerOrder(execute[0], execute[1], execute[2], execute[3], message.header().commandId(), true);
    }

    void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId) {
        executeTriggerOrder(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId, true);
    }

    void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId, boolean cancelOco) {
        var trigger = owner.runtimeState.triggerOrder(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        }
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
            return;
        }
        Integer triggerSymbolId = owner.identities.findSymbolId(trigger.symbol());
        var mark = triggerSymbolId == null ? null : owner.runtimeState.markPrice(triggerSymbolId);
        if (mark != null && (mark.priceSequence() != triggerSequence
                || mark.markPriceTicks() != triggeredPriceTicks
                || !isTriggerConditionSatisfied(trigger, triggeredPriceTicks))) {
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        }
        if (cancelOco) owner.cancelAllOcoSiblings(trigger);
        if (RuntimeCommandProcessor.claimTriggerOrder(owner.runtimeState, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis)) {
            owner.commits.requestCommitPublication();
        }
        var instrument = owner.runtimeState.instrument(trigger.symbol());
        if (instrument == null || instrument.changeId() <= 0 || trigger.instrumentChangeId() <= 0
                || instrument.changeId() != trigger.instrumentChangeId()) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_CHANGE_ID",
                    triggeredAtEpochMillis);
            return;
        }
        long childOrderId = triggerChildOrderId(triggerOrderId, owner.runtimeState);
        boolean spot = instrument.contractType() == com.surprising.instrument.api.model.ContractType.SPOT;
        long limitPriceTicks = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (trigger.priceTicks() > 0 ? trigger.priceTicks() : triggeredPriceTicks) : 0;
        var place = new com.surprising.aeron.protocol.PlaceOrderCommand(
                childOrderId, trigger.symbol(), trigger.instrumentChangeId(), trigger.side(), limitPriceTicks,
                trigger.quantitySteps(), !spot, trigger.marginMode(), trigger.positionSide(),
                trigger.orderType(), trigger.timeInForce(), false, "TRIGGER:" + triggerOrderId);
        owner.requireOrderIdentityAvailable(trigger.userId(), place);
        try {
            long childCoreSequence = Math.addExact(Math.addExact(owner.appliedCommandCount, 2), owner.admissions.queuedMatching.size());
            owner.reservePlaceOrderRuntime(trigger.userId(), place, commandId, childCoreSequence);
        } catch (CoreStateRejectedException exception) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0, exception.code(), triggeredAtEpochMillis);
            return;
        }
        owner.resultBuilder.markUserChanged(trigger.userId());
        owner.resultBuilder.markOrderChanged(childOrderId);
        owner.queueTriggerMatching(trigger, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId);
        owner.resultBuilder.commandOrderViews = TradingCoreRuntime.appendDistinct(owner.resultBuilder.commandOrderViews, List.of(owner.runtimeOrderView(childOrderId)));
    }

    void cancelTriggerOrderRuntime(long userId, long triggerOrderId) {
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.cancelTriggerOrder(
                        owner.runtimeState, userId, triggerOrderId))) {
            owner.commits.requestCommitPublication();
        }
    }

    void claimTriggerOrderRuntime(long triggerOrderId, long triggerSequence,
                                          long triggeredPriceTicks, long triggeredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.claimTriggerOrder(owner.runtimeState, triggerOrderId,
                        triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis))) {
            owner.commits.requestCommitPublication();
        }
    }

    void completeTriggerOrderRuntime(long triggerOrderId, boolean success, long placedOrderId,
                                             String rejectReason, long completedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.completeTriggerOrder(owner.runtimeState, triggerOrderId, success,
                        placedOrderId, rejectReason, completedAtEpochMillis))) {
            owner.commits.requestCommitPublication();
        }
    }

    void expireTriggerOrderRuntime(long triggerOrderId, long expiredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.expireTriggerOrder(
                        owner.runtimeState, triggerOrderId, expiredAtEpochMillis))) {
            owner.commits.requestCommitPublication();
        }
    }

    void updateTriggerTrailingRuntime(long triggerOrderId, long highestPriceTicks,
                                              long lowestPriceTicks, long activatedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.updateTriggerTrailing(owner.runtimeState, triggerOrderId,
                        highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis))) {
            owner.commits.requestCommitPublication();
        }
    }

    void retryTriggerOrderRuntime(long triggerOrderId, long staleBeforeEpochMillis,
                                          long retryAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState.executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.retryTriggerOrder(owner.runtimeState, triggerOrderId,
                        staleBeforeEpochMillis, retryAtEpochMillis))) {
            owner.commits.requestCommitPublication();
        }
    }

    long preparedTriggerPositionKey(
            long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        return RuntimeCommandProcessor.triggerPositionKey(
                owner.identities, owner.productLine, userId, trigger);
    }

    static long triggerChildOrderId(long triggerOrderId, TradingRuntimeState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.order(candidate) != null) {
            candidate = Math.addExact(candidate, 2);
        }
        return candidate;
    }

    void executeUpsertAlgoOrder(CoreMessage message, long clusterTimestamp) {
        var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payloadUnsafe())
                .materializeCreation(clusterTimestamp);
        if (owner.runtimeState.algoOrder(algo.algoOrderId()) == null
                && owner.terminalRetention.containsAlgo(algo.algoOrderId(), message.header().userId(),
                algo.clientAlgoOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                    "terminal algo order identity is retained");
        }
        RuntimeCommandProcessor.upsertAlgoOrder(owner.runtimeState, owner.identities,
                message.header().userId(), algo);
        owner.commits.requestCommitPublication();
    }

    void executeUpdateCancelAllAfter(CoreMessage message, long clusterTimestamp) {
        RuntimeCommandProcessor.updateCancelAllAfter(owner.runtimeState, message.header().userId(),
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payloadUnsafe()));
        owner.commits.requestCommitPublication();
    }

    void executePlaceTriggerOrder(CoreMessage message, long clusterTimestamp) {
        var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payloadUnsafe())
                .materializeCreation(clusterTimestamp);
        var maintenanceInstrument = owner.runtimeState.instrument(trigger.symbol());
        if (maintenanceInstrument != null) maintenanceInstrument.requireTrading(false);
        if (owner.runtimeState.triggerOrder(trigger.triggerOrderId()) == null
                && owner.terminalRetention.containsTrigger(trigger.triggerOrderId(), message.header().userId(),
                trigger.clientTriggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "terminal trigger order identity is retained");
        }
        int symbolId = owner.identities.symbolId(trigger.symbol());
        long positionKey = preparedTriggerPositionKey(message.header().userId(), trigger);
        boolean instrumentSettled = owner.runtimeState.treasury().lifecycleSettlement(symbolId) != 0;
        owner.runtimeState.executeUserSettlement(message.header().userId(), () -> {
            RuntimeCommandProcessor.upsertTriggerOrder(owner.runtimeState,
                    message.header().userId(), trigger, symbolId, positionKey, instrumentSettled);
            return null;
        });
        owner.commits.requestCommitPublication();
        owner.resultBuilder.commandTriggerOrderView = owner.runtimeState.triggerOrder(trigger.triggerOrderId()).view();
    }

    void executeCancelTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payloadUnsafe());
        cancelTriggerOrderRuntime(message.header().userId(), triggerOrderId);
    }

    void executeClaimTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payloadUnsafe());
        claimTriggerOrderRuntime(claim[0], claim[1], claim[2], claim[3]);
    }

    void executeCompleteTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payloadUnsafe());
        completeTriggerOrderRuntime(complete[0], complete[1] == 1, complete[2], "", complete[3]);
    }

    void executeUpdateTriggerTrailing(CoreMessage message, long clusterTimestamp) {
        long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payloadUnsafe());
        updateTriggerTrailingRuntime(trailing[0], trailing[1], trailing[2], trailing[3]);
    }

    void executeExpireTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        expireTriggerOrderRuntime(lifecycle[0], lifecycle[1]);
    }

    void executeRetryTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        retryTriggerOrderRuntime(lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis());
    }

    void executeExecuteTriggerOrder(CoreMessage message, long clusterTimestamp) {
executeTriggerOrder(message);
    }

    void cancelTriggersForClosedPositions() {
        owner.seedChangeAccumulators();
        if (!owner.runtimeState.hasChangedPositions()) return;
        org.eclipse.collections.api.iterator.LongIterator changedPositions =
                owner.runtimeState.changedPositionIterator();
        while (changedPositions.hasNext()) {
            long positionKey = changedPositions.next();
            var previous = owner.runtimeState.currentPatchPositionBefore(positionKey);
            if (previous == null || previous.signedQuantitySteps() == 0) continue;
            var current = owner.runtimeState.position(positionKey);
            if (current != null && current.signedQuantitySteps() != 0) continue;
            var identity = owner.identities.positionIdentity(positionKey);
            var previousMarginMode = previous.marginMode();
            for (long triggerOrderId : owner.triggerOrderIndex.ids(identity.userId())) {
                var trigger = owner.runtimeState.triggerOrder(triggerOrderId);
                if (trigger == null || trigger.status()
                        != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                String triggerPositionIdentity = trigger.positionSide()
                        == com.surprising.aeron.protocol.CorePositionSide.NET
                        ? trigger.symbol() : trigger.symbol() + ':' + trigger.positionSide().name();
                if (!identity.positionKey().equals(triggerPositionIdentity)
                        || trigger.marginMode() != previousMarginMode) continue;
                cancelTriggerOrderRuntime(identity.userId(), triggerOrderId);
            }
        }
    }

    long requireTriggerOwner(long triggerOrderId) {
        var trigger = owner.runtimeState.triggerOrder(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        return trigger.userId();
    }
}
