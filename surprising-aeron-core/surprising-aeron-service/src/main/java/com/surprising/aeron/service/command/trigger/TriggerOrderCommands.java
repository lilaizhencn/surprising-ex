package com.surprising.aeron.service.command.trigger;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.state.CoreStateRejectedException;
import com.surprising.aeron.service.state.RiskScanRuntime;
import com.surprising.aeron.service.state.index.TriggerOrderIndex;
import com.surprising.aeron.service.state.RuntimeCommandProcessor;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.command.support.PrimitiveLongChangeSet;
import java.util.UUID;

import com.surprising.aeron.protocol.CoreOrderSide;

/** 触发单和算法单生命周期命令；各产品线使用所属运行时和订单规则。 */
public final class TriggerOrderCommands {
    /** 唯一 owner；仅在其线程访问共享交易状态和提交边界。 */
    private final TriggerCommandContext owner;

    /** Owner selection scratch, copied into the existing commit event before the next command. */
    private final PrimitiveLongChangeSet closingTriggerIds = new PrimitiveLongChangeSet();
    /** Reused owner scratch for one OCO cancellation page; never escapes this command. */
    private final PrimitiveLongChangeSet ocoSiblingPage = new PrimitiveLongChangeSet(64);
    private static final java.util.function.BooleanSupplier COMPLETE = () -> true;
    /** Risk-trigger scanning is serialized by the owner command loop, so one work object is enough. */
    private final PendingTriggerScan reusableTriggerScan = new PendingTriggerScan();
    /** Trigger child execution is also serialized and can reuse its Lane operation and continuation. */
    private final TriggerExecutionContinuation reusableTriggerExecution = new TriggerExecutionContinuation();

    public PrimitiveLongChangeSet closingTriggerIds() { return closingTriggerIds; }

    public PrimitiveLongChangeSet collectClosingTriggerIds() {
        closingTriggerIds.clear();
        selectClosingTriggers(id -> {
            Math.incrementExact(owner.runtimeState().triggerOrder(id).revision());
            closingTriggerIds.add(id);
        });
        Math.addExact(owner.runtimeState().revision(), closingTriggerIds.size());
        return closingTriggerIds;
    }

    public TriggerOrderCommands(TriggerCommandContext owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        Integer symbolId = owner.identities().findSymbolId(command.symbol());
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = owner.triggerOrderIndex().maxPendingId(command.symbol());
        RuntimeCommandProcessor.replaceRiskScan(owner.runtimeState(),
                scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    /** One bounded page cursor; account mutations use the same permanent Lane tasks as explicit triggers. */
    public java.util.function.BooleanSupplier pendingTriggerScan(String symbol, int maxWork) {
        Integer symbolId = owner.identities().findSymbolId(symbol);
        RiskScanRuntime scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return COMPLETE;
        return reusableTriggerScan.prepare(symbol, scan,
                Math.min(maxWork, owner.defaultTriggerScanBatchSize()));
    }

    public void evaluatePendingTriggerScan(String symbol, int maxWork) {
        var work = pendingTriggerScan(symbol, maxWork);
        if (!work.getAsBoolean()) throw new IllegalStateException("asynchronous trigger scan requires continuation");
    }

    private final class PendingTriggerScan implements java.util.function.BooleanSupplier {
        private String symbol;
        private UUID commandId;
        private RiskScanRuntime scan;
        private int remaining;
        private TriggerOrderIndex.TriggerCandidatePage page;
        private int candidate;
        private boolean ocoComplete, finished;
        private java.util.function.BooleanSupplier pending;
        private final ScanMutation reusableMutation = new ScanMutation();

        PendingTriggerScan() {
        }

        PendingTriggerScan prepare(String symbol, RiskScanRuntime scan, int remaining) {
            this.symbol = symbol;
            this.scan = scan;
            this.remaining = remaining;
            commandId = UUID.nameUUIDFromBytes((owner.productLine().name() + ":MARK_PRICE:" + symbol + ":"
                    + scan.priceSequence()).getBytes(java.nio.charset.StandardCharsets.UTF_8));
            candidate = 0;
            page = null;
            pending = null;
            ocoComplete = false;
            finished = false;
            return this;
        }

        @Override public boolean getAsBoolean() {
            for (;;) {
                if (pending != null) {
                    if (!pending.getAsBoolean()) return false;
                    pending = null;
                }
                if (finished) return true;
                if (page == null) {
                    if (scan.triggerOcoOrderId() != 0) {
                        var trigger = owner.runtimeState().triggerOrder(scan.triggerOcoOrderId());
                        if (trigger != null && trigger.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                            cancelSiblings(trigger, scan.triggerOcoCursor() == 0 ? Long.MAX_VALUE : scan.triggerOcoCursor(), true);
                            continue;
                        }
                        scan = scan.withTriggerOcoProgress(0, 0);
                        replaceRiskScan(scan);
                    }
                    if (remaining <= 0) return true;
                        page = owner.triggerOrderIndex().candidatesPage(symbol, scan.triggerMarkPriceTicks(), scan.triggerPhase(),
                            scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(), remaining);
                }
                if (candidate == page.ids().size()) {
                    replaceRiskScan(scan.withTriggerProgress(page.complete(), page.nextPhase(), page.nextPriceCursor(),
                            page.nextOrderCursor(), scan.triggerUpperId(), scan.triggerMarkPriceTicks(),
                            scan.triggerGeneratedAtEpochMillis()).withTriggerOcoProgress(0, 0));
                    finished = true;
                    return true;
                }
                long id = page.idAt(candidate);
                var trigger = owner.runtimeState().triggerOrder(id);
                if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                    candidate++;
                    continue;
                }
                long price = scan.triggerMarkPriceTicks(), at = scan.triggerGeneratedAtEpochMillis();
                if (trigger.expiresAtEpochMillis() > 0 && at > 0 && trigger.expiresAtEpochMillis() <= at) {
                    candidate++;
                    mutateExpire(trigger.userId(), id, at);
                    continue;
                }
                if (trigger.triggerType() == com.surprising.aeron.protocol.CoreTriggerOrderType.TRAILING_STOP) {
                    boolean sell = trigger.side() == CoreOrderSide.SELL;
                    if (trigger.activationPriceTicks() > 0 && ((sell && price < trigger.activationPriceTicks())
                            || (!sell && price > trigger.activationPriceTicks()))) {
                        candidate++;
                        continue;
                    }
                    long highest = sell ? Math.max(trigger.highestPriceTicks(), price) : trigger.highestPriceTicks();
                    long lowest = sell ? trigger.lowestPriceTicks()
                            : trigger.lowestPriceTicks() == 0 ? price : Math.min(trigger.lowestPriceTicks(), price);
                    long activated = trigger.activatedAtEpochMillis() == 0 ? at : trigger.activatedAtEpochMillis();
                    if (highest != trigger.highestPriceTicks() || lowest != trigger.lowestPriceTicks()
                            || activated != trigger.activatedAtEpochMillis()) {
                        mutateTrailing(trigger.userId(), id, highest, lowest, activated);
                        continue;
                    }
                }
                if (!isTriggerConditionSatisfied(trigger, price)) {
                    candidate++;
                    continue;
                }
                if (!ocoComplete) {
                    if (remaining <= 0) {
                        replaceRiskScan(scan.withTriggerOcoProgress(id, Long.MAX_VALUE));
                        finished = true;
                        return true;
                    }
                    cancelSiblings(trigger, Long.MAX_VALUE, false);
                    continue;
                }
                ocoComplete = false;
                candidate++;
                if (owner.runtimeState().asynchronousCommands()) {
                    pending = beginTriggerExecution(id, scan.priceSequence(), price, at, commandId, false);
                } else {
                    executeTriggerOrder(id, scan.priceSequence(), price, at, commandId, false);
                }
            }
        }

        private void cancelSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                    long cursor, boolean resumed) {
            // The control fence keeps the Owner index stable until Lane publication is collected.
            // Pass the bounded ID range, not a copied list of sibling identities.
            var siblings = owner.triggerOrderIndex().ocoSiblings(trigger).descendingSet();
            int count = 0;
            long last = cursor;
            boolean more = false;
            for (long id : siblings) {
                if (id == trigger.triggerOrderId() || id >= cursor) continue;
                if (count == remaining) { more = true; break; }
                count++;
                last = id;
            }
            boolean complete = !more;
            int work = count;
            long next = last;
            if (work == 0) {
                reusableMutation.completeSiblings(trigger, next, work, complete, resumed);
                return;
            }
            reusableMutation.prepareSiblings(trigger, cursor, next, work, complete, resumed, siblings);
            submitMutation(trigger.userId());
        }

        private void mutateExpire(long userId, long triggerId, long at) {
            reusableMutation.prepareExpire(triggerId, at);
            submitMutation(userId);
        }

        private void mutateTrailing(long userId, long triggerId, long highest, long lowest, long activated) {
            reusableMutation.prepareTrailing(triggerId, highest, lowest, activated);
            submitMutation(userId);
        }

        private void submitMutation(long userId) {
            reusableMutation.userId = userId;
            if (owner.runtimeState().asynchronousCommands()) {
                pending = reusableMutation;
                owner.runtimeState().dispatchControlLanes(
                        1L << owner.runtimeState().topology().accountLaneId(userId), reusableMutation);
            } else {
                Object result = owner.runtimeState().executeUserSettlement(userId, reusableMutation);
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
                reusableMutation.completeMutation();
            }
        }

        private final class ScanMutation implements java.util.function.BooleanSupplier,
                java.util.function.IntFunction<Object>, java.util.function.Supplier<Object> {
            private static final byte EXPIRE = 1, TRAILING = 2, SIBLINGS = 3;
            private byte kind;
            private long userId, triggerId, arg1, arg2, arg3, cursor, next;
            private int work;
            private boolean complete, resumed;
            private com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger;
            private java.util.NavigableSet<Long> siblings;

            void prepareExpire(long triggerId, long at) {
                clear(); kind = EXPIRE; this.triggerId = triggerId; arg1 = at;
            }

            void prepareTrailing(long triggerId, long highest, long lowest, long activated) {
                clear(); kind = TRAILING; this.triggerId = triggerId;
                arg1 = highest; arg2 = lowest; arg3 = activated;
            }

            void prepareSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                 long cursor, long next, int work, boolean complete, boolean resumed,
                                 java.util.NavigableSet<Long> siblings) {
                clear(); kind = SIBLINGS; this.trigger = trigger; this.cursor = cursor; this.next = next;
                this.work = work; this.complete = complete; this.resumed = resumed; this.siblings = siblings;
            }

            void completeSiblings(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                                  long next, int work, boolean complete, boolean resumed) {
                this.trigger = trigger; this.next = next; this.work = work;
                this.complete = complete; this.resumed = resumed; finishSiblings();
            }

            private Object applyMutation() {
                return switch (kind) {
                    case EXPIRE -> RuntimeCommandProcessor.expireTriggerOrder(owner.runtimeState(), triggerId, arg1);
                    case TRAILING -> RuntimeCommandProcessor.updateTriggerTrailing(owner.runtimeState(), triggerId,
                            arg1, arg2, arg3);
                    case SIBLINGS -> {
                        boolean changed = false;
                        for (long id : siblings) {
                            if (id == trigger.triggerOrderId() || id >= cursor) continue;
                            if (id < next) break;
                            var sibling = owner.runtimeState().triggerOrder(id);
                            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                                changed |= RuntimeCommandProcessor.cancelTriggerOrder(owner.runtimeState(),
                                        trigger.userId(), id);
                        }
                        yield changed;
                    }
                    default -> throw new IllegalStateException("unknown trigger scan mutation");
                };
            }

            void completeMutation() {
                if (kind == SIBLINGS) finishSiblings();
            }

            private void finishSiblings() {
                remaining -= work;
                if (!complete) {
                    scan = scan.withTriggerOcoProgress(trigger.triggerOrderId(), next);
                    replaceRiskScan(scan);
                    finished = true;
                } else if (resumed) {
                    scan = scan.withTriggerOcoProgress(0, 0);
                    replaceRiskScan(scan);
                } else ocoComplete = true;
            }

            void clear() {
                kind = 0; userId = triggerId = arg1 = arg2 = arg3 = cursor = next = 0;
                work = 0; complete = resumed = false; trigger = null; siblings = null;
            }

            @Override public Object apply(int ignoredLaneId) { return applyMutation(); }
            @Override public Object get() { return applyMutation(); }

            @Override public boolean getAsBoolean() {
                if (!owner.runtimeState().pollControlLanes()) return false;
                Object result = owner.runtimeState().controlLaneResult(
                        owner.runtimeState().topology().accountLaneId(userId));
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
                completeMutation();
                return true;
            }
        }
    }

    void replaceRiskScan(RiskScanRuntime scan) {
        RuntimeCommandProcessor.replaceRiskScan(owner.runtimeState(), scan);
        owner.requestCommitPublication();
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

    public TriggerCommandContext.OcoCancellationPage cancelOcoSiblings(
            com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger, long cursor, int limit) {
        if (limit <= 0) return new TriggerCommandContext.OcoCancellationPage(false, cursor, 0);
        ocoSiblingPage.clear();
        boolean more = false;
        for (Long siblingId : owner.triggerOrderIndex().ocoSiblings(trigger).descendingSet()) {
            if (siblingId == null || siblingId == trigger.triggerOrderId() || siblingId >= cursor) continue;
            if (ocoSiblingPage.size() >= limit) {
                more = true;
                break;
            }
            ocoSiblingPage.add(siblingId.longValue());
        }
        long nextCursor = cursor;
        int work = 0;
        for (int index = 0; index < ocoSiblingPage.size(); index++) {
            long siblingId = ocoSiblingPage.valueAt(index);
            nextCursor = siblingId;
            work++;
            var sibling = owner.runtimeState().triggerOrder(siblingId);
            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                cancelTriggerOrderRuntime(sibling.userId(), siblingId);
            }
        }
        return new TriggerCommandContext.OcoCancellationPage(!more, nextCursor, work);
    }

    public void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        executeTriggerOrder(execute[0], execute[1], execute[2], execute[3], message.header().commandId(), true);
    }

    public void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId) {
        executeTriggerOrder(triggerOrderId, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId, true);
    }

    public void executeTriggerOrder(long triggerOrderId, long triggerSequence, long triggeredPriceTicks,
                                     long triggeredAtEpochMillis, UUID commandId, boolean cancelOco) {
        var trigger = executableTrigger(triggerOrderId, triggerSequence, triggeredPriceTicks);
        if (trigger == null) return;
        if (cancelOco) owner.cancelAllOcoSiblings(trigger);
        if (RuntimeCommandProcessor.claimTriggerOrder(owner.runtimeState(), triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis)) {
            owner.requestCommitPublication();
        }
        var instrument = owner.runtimeState().instrument(trigger.symbol());
        if (instrument == null || instrument.changeId() <= 0 || trigger.instrumentChangeId() <= 0
                || instrument.changeId() != trigger.instrumentChangeId()) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0,
                    instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_CHANGE_ID",
                    triggeredAtEpochMillis);
            return;
        }
        var place = childOrder(trigger, triggeredPriceTicks, instrument);
        long childOrderId = place.orderId();
        owner.requireOrderIdentityAvailable(trigger.userId(), place);
        try {
            long childCoreSequence = Math.addExact(Math.addExact(owner.appliedCommandCount(), 2), owner.queuedMatchingCount());
            owner.reservePlaceOrderRuntime(trigger.userId(), place, commandId, childCoreSequence);
        } catch (CoreStateRejectedException exception) {
            completeTriggerOrderRuntime(triggerOrderId, false, 0, exception.code(), triggeredAtEpochMillis);
            return;
        }
        owner.markUserChanged(trigger.userId());
        owner.markOrderChanged(childOrderId);
        owner.queueTriggerMatching(trigger, triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis, commandId, childOrderId);
    }

    private com.surprising.aeron.service.state.model.CoreTriggerOrderState executableTrigger(
            long triggerId, long sequence, long price) {
        var trigger = owner.runtimeState().triggerOrder(triggerId);
        if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) return null;
        Integer symbolId = owner.identities().findSymbolId(trigger.symbol());
        var mark = symbolId == null ? null : owner.runtimeState().markPrice(symbolId);
        if (mark != null && (mark.priceSequence() != sequence || mark.markPriceTicks() != price
                || !isTriggerConditionSatisfied(trigger, price)))
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        return trigger;
    }

    private PlaceOrderCommand childOrder(com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
            long price, com.surprising.aeron.service.state.CoreInstrumentState instrument) {
        long limit = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (trigger.priceTicks() > 0 ? trigger.priceTicks() : price) : 0;
        return new PlaceOrderCommand(triggerChildOrderId(trigger.triggerOrderId(), owner.runtimeState()),
                trigger.symbol(), trigger.instrumentChangeId(), trigger.side(), limit, trigger.quantitySteps(),
                instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT,
                trigger.marginMode(), trigger.positionSide(), trigger.orderType(), trigger.timeInForce(), false,
                "TRIGGER:" + trigger.triggerOrderId());
    }

    void cancelTriggerOrderRuntime(long userId, long triggerOrderId) {
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.cancelTriggerOrder(
                        owner.runtimeState(), userId, triggerOrderId))) {
            owner.requestCommitPublication();
        }
    }

    void claimTriggerOrderRuntime(long triggerOrderId, long triggerSequence,
                                          long triggeredPriceTicks, long triggeredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.claimTriggerOrder(owner.runtimeState(), triggerOrderId,
                        triggerSequence, triggeredPriceTicks, triggeredAtEpochMillis))) {
            owner.requestCommitPublication();
        }
    }

    void completeTriggerOrderRuntime(long triggerOrderId, boolean success, long placedOrderId,
                                             String rejectReason, long completedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.completeTriggerOrder(owner.runtimeState(), triggerOrderId, success,
                        placedOrderId, rejectReason, completedAtEpochMillis))) {
            owner.requestCommitPublication();
        }
    }

    void expireTriggerOrderRuntime(long triggerOrderId, long expiredAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.expireTriggerOrder(
                        owner.runtimeState(), triggerOrderId, expiredAtEpochMillis))) {
            owner.requestCommitPublication();
        }
    }

    void updateTriggerTrailingRuntime(long triggerOrderId, long highestPriceTicks,
                                              long lowestPriceTicks, long activatedAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.updateTriggerTrailing(owner.runtimeState(), triggerOrderId,
                        highestPriceTicks, lowestPriceTicks, activatedAtEpochMillis))) {
            owner.requestCommitPublication();
        }
    }

    void retryTriggerOrderRuntime(long triggerOrderId, long staleBeforeEpochMillis,
                                          long retryAtEpochMillis) {
        long userId = requireTriggerOwner(triggerOrderId);
        if (owner.runtimeState().executeUserSettlement(userId,
                () -> RuntimeCommandProcessor.retryTriggerOrder(owner.runtimeState(), triggerOrderId,
                        staleBeforeEpochMillis, retryAtEpochMillis))) {
            owner.requestCommitPublication();
        }
    }

    long preparedTriggerPositionKey(
            long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        return RuntimeCommandProcessor.triggerPositionKey(
                owner.identities(), owner.productLine(), userId, trigger);
    }

    static long triggerChildOrderId(long triggerOrderId, TradingRuntimeState state) {
        long candidate = Math.addExact(Math.multiplyExact(triggerOrderId, 2), 1);
        while (state.order(candidate) != null) {
            candidate = Math.addExact(candidate, 2);
        }
        return candidate;
    }

    public void executeUpsertAlgoOrder(CoreMessage message, long clusterTimestamp) {
        var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decode(message.payloadUnsafe())
                .materializeCreation(clusterTimestamp);
        if (owner.runtimeState().algoOrder(algo.algoOrderId()) == null
                && owner.terminalAlgoRetained(algo.algoOrderId(), message.header().userId(),
                algo.clientAlgoOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_ALGO_ORDER_ID",
                    "terminal algo order identity is retained");
        }
        if (owner.runtimeState().asynchronousCommands()) {
            var current = owner.runtimeState().algoOrder(algo.algoOrderId());
            if (current != null && current.userId() != message.header().userId())
                throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
            int symbolId = owner.identities().symbolId(algo.symbol());
            owner.deferAlgoUpsert(message.header().userId(), algo, symbolId);
            return;
        }
        RuntimeCommandProcessor.upsertAlgoOrder(owner.runtimeState(), owner.identities(),
                message.header().userId(), algo);
        owner.requestCommitPublication();
    }

    public void executeUpdateCancelAllAfter(CoreMessage message, long clusterTimestamp) {
        RuntimeCommandProcessor.updateCancelAllAfter(owner.runtimeState(), message.header().userId(),
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    public void executePlaceTriggerOrder(CoreMessage message, long clusterTimestamp) {
        var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeState(message.payloadUnsafe())
                .materializeCreation(clusterTimestamp);
        var maintenanceInstrument = owner.runtimeState().instrument(trigger.symbol());
        if (maintenanceInstrument != null) maintenanceInstrument.requireTrading(false);
        if (owner.runtimeState().triggerOrder(trigger.triggerOrderId()) == null
                && owner.terminalTriggerRetained(trigger.triggerOrderId(), message.header().userId(),
                trigger.clientTriggerOrderId())) {
            throw new CoreStateRejectedException("DUPLICATE_CLIENT_TRIGGER_ORDER_ID",
                    "terminal trigger order identity is retained");
        }
        int symbolId = owner.identities().symbolId(trigger.symbol());
        long positionKey = preparedTriggerPositionKey(message.header().userId(), trigger);
        boolean instrumentSettled = owner.runtimeState().treasury().lifecycleSettlement(symbolId) != 0;
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerUpsert(message.header().userId(), trigger, symbolId, positionKey,
                    instrumentSettled);
            return;
        }
        owner.runtimeState().executeUserSettlement(message.header().userId(), () -> {
            RuntimeCommandProcessor.upsertTriggerOrder(owner.runtimeState(),
                    message.header().userId(), trigger, symbolId, positionKey, instrumentSettled);
            return null;
        });
        owner.requestCommitPublication();
        owner.setCommandTriggerOrderView(owner.runtimeState().triggerOrder(trigger.triggerOrderId()).view());
    }

    public void executeCancelTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(message.header().userId(), TriggerCommandContext.Mutation.CANCEL,
                    triggerOrderId, 0, 0, 0, false, null);
            return;
        }
        cancelTriggerOrderRuntime(message.header().userId(), triggerOrderId);
    }

    public void executeClaimTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(requireTriggerOwner(claim[0]), TriggerCommandContext.Mutation.CLAIM,
                    claim[0], claim[1], claim[2], claim[3], false, null);
            return;
        }
        claimTriggerOrderRuntime(claim[0], claim[1], claim[2], claim[3]);
    }

    public void executeCompleteTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(requireTriggerOwner(complete[0]), TriggerCommandContext.Mutation.COMPLETE,
                    complete[0], complete[2], complete[3], 0, complete[1] == 1, "");
            return;
        }
        completeTriggerOrderRuntime(complete[0], complete[1] == 1, complete[2], "", complete[3]);
    }

    public void executeUpdateTriggerTrailing(CoreMessage message, long clusterTimestamp) {
        long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(requireTriggerOwner(trailing[0]), TriggerCommandContext.Mutation.TRAILING,
                    trailing[0], trailing[1], trailing[2], trailing[3], false, null);
            return;
        }
        updateTriggerTrailingRuntime(trailing[0], trailing[1], trailing[2], trailing[3]);
    }

    public void executeExpireTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(requireTriggerOwner(lifecycle[0]), TriggerCommandContext.Mutation.EXPIRE,
                    lifecycle[0], lifecycle[1], 0, 0, false, null);
            return;
        }
        expireTriggerOrderRuntime(lifecycle[0], lifecycle[1]);
    }

    public void executeRetryTriggerOrder(CoreMessage message, long clusterTimestamp) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        if (owner.runtimeState().asynchronousCommands()) {
            owner.deferTriggerMutation(requireTriggerOwner(lifecycle[0]), TriggerCommandContext.Mutation.RETRY,
                    lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis(), 0, false, null);
            return;
        }
        retryTriggerOrderRuntime(lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis());
    }

    public void executeExecuteTriggerOrder(CoreMessage message, long clusterTimestamp) {
        if (!owner.runtimeState().asynchronousCommands()) {
            executeTriggerOrder(message);
            return;
        }
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        long triggerId = execute[0], triggerSequence = execute[1], price = execute[2], triggeredAt = execute[3];
        owner.deferControl(beginTriggerExecution(triggerId, triggerSequence, price, triggeredAt,
                message.header().commandId(), true));
    }

    private java.util.function.BooleanSupplier beginTriggerExecution(long triggerId, long triggerSequence,
            long price, long triggeredAt, UUID commandId, boolean cancelOco) {
        var trigger = executableTrigger(triggerId, triggerSequence, price);
        if (trigger == null) return COMPLETE;
        var instrument = owner.runtimeState().instrument(trigger.symbol());
        com.surprising.aeron.service.state.ResolvedPlaceOrder resolved = null;
        String failureReason = "";
        if (instrument == null || instrument.changeId() <= 0 || trigger.instrumentChangeId() <= 0
                || instrument.changeId() != trigger.instrumentChangeId()) {
            failureReason = instrument == null ? "INSTRUMENT_NOT_FOUND" : "STALE_INSTRUMENT_CHANGE_ID";
        } else {
            var place = childOrder(trigger, price, instrument);
            owner.requireOrderIdentityAvailable(trigger.userId(), place);
            try {
                resolved = com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.resolve(
                        owner.runtimeState(), owner.identities(), trigger.userId(), place, owner.currentClusterTimestamp());
            } catch (CoreStateRejectedException rejected) { failureReason = rejected.code(); }
        }
        return reusableTriggerExecution.prepare(trigger, triggerId, triggerSequence, price, triggeredAt,
                commandId, cancelOco, resolved, failureReason);
    }

    /** Reusable Lane operation for a trigger child; keeps the large capture graph off each command. */
    private final class TriggerExecutionContinuation
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        private com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger;
        private long triggerId, triggerSequence, price, triggeredAt, childSequence;
        private UUID commandId;
        private boolean cancelOco;
        private com.surprising.aeron.service.state.ResolvedPlaceOrder order;
        private String failureReason;
        private com.surprising.aeron.service.state.admission.AdmissionIdentity identity;
        private int assetId, laneId;
        private long openInterest;
        private java.util.NavigableSet<Long> siblings;

        java.util.function.BooleanSupplier prepare(
                com.surprising.aeron.service.state.model.CoreTriggerOrderState trigger,
                long triggerId, long triggerSequence, long price, long triggeredAt, UUID commandId,
                boolean cancelOco, com.surprising.aeron.service.state.ResolvedPlaceOrder order,
                String failureReason) {
            this.trigger = trigger;
            this.triggerId = triggerId;
            this.triggerSequence = triggerSequence;
            this.price = price;
            this.triggeredAt = triggeredAt;
            this.commandId = commandId;
            this.cancelOco = cancelOco;
            this.order = order;
            this.failureReason = failureReason;
            identity = order == null ? null : com.surprising.aeron.service.state.RuntimeOrderAdmission.admissionIdentity(
                    owner.runtimeState(), owner.identities(), trigger.userId(), order);
            assetId = order == null ? 0 : owner.identities().assetId(order.reservationAsset());
            openInterest = owner.openInterestIndex().openInterestSteps(trigger.symbol());
            childSequence = Math.addExact(Math.addExact(owner.appliedCommandCount(), 2), owner.queuedMatchingCount());
            siblings = cancelOco ? owner.triggerOrderIndex().ocoSiblings(trigger).descendingSet()
                    : java.util.Collections.emptyNavigableSet();
            laneId = owner.runtimeState().topology().accountLaneId(trigger.userId());
            owner.runtimeState().dispatchControlLanes(1L << laneId, this);
            return this;
        }

        @Override public Object apply(int ignoredLaneId) {
            for (long siblingId : siblings) {
                if (siblingId == triggerId) continue;
                var sibling = owner.runtimeState().triggerOrder(siblingId);
                if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                    RuntimeCommandProcessor.cancelTriggerOrder(owner.runtimeState(), trigger.userId(), siblingId);
            }
            RuntimeCommandProcessor.claimTriggerOrder(owner.runtimeState(), triggerId, triggerSequence, price, triggeredAt);
            if (!failureReason.isEmpty()) {
                RuntimeCommandProcessor.completeTriggerOrder(owner.runtimeState(), triggerId, false, 0,
                        failureReason, triggeredAt);
                return null;
            }
            var key = owner.identities().prepareClientKeyInCurrentLane(trigger.userId(), order.clientOrderId());
            try {
                RuntimeCommandProcessor.placeTriggerChildInLane(owner.runtimeState(), trigger.userId(), order,
                        commandId, childSequence, openInterest, identity, key.key(), assetId);
                return key;
            } catch (CoreStateRejectedException rejected) {
                if (key.allocated()) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                RuntimeCommandProcessor.completeTriggerOrder(owner.runtimeState(), triggerId, false, 0,
                        rejected.code(), triggeredAt);
                return null;
            } catch (RuntimeException | Error failure) {
                if (key.allocated()) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                throw failure;
            }
        }

        @Override public boolean getAsBoolean() {
            if (!owner.runtimeState().pollControlLanes()) return false;
            var clientKey = (com.surprising.aeron.service.state.RuntimeIdentityRegistry.PreparedClientKey)
                    owner.runtimeState().controlLaneResult(laneId);
            if (clientKey != null) owner.identities().recordLaneClientAllocations(clientKey.newIdentity() ? 1 : 0);
            owner.requestCommitPublication();
            if (clientKey != null) {
                owner.runtimeState().collectControlReservation(trigger.userId(), order.orderId(), childSequence);
                owner.markUserChanged(trigger.userId());
                owner.markOrderChanged(order.orderId());
                owner.queueTriggerMatching(trigger, triggerSequence, price, triggeredAt, commandId, order.orderId());
            }
            return true;
        }
    }

    public void cancelTriggersForClosedPositions() {
        selectClosingTriggers(id -> cancelTriggerOrderRuntime(requireTriggerOwner(id), id));
    }

    private void selectClosingTriggers(java.util.function.LongConsumer selected) {
        owner.seedChangeAccumulators();
        if (!owner.runtimeState().hasChangedPositions()) return;
        org.eclipse.collections.api.iterator.LongIterator changedPositions =
                owner.runtimeState().changedPositionIterator();
        while (changedPositions.hasNext()) {
            long positionKey = changedPositions.next();
            var previous = owner.runtimeState().currentPatchPositionBefore(positionKey);
            if (previous == null || previous.signedQuantitySteps() == 0) continue;
            var current = owner.runtimeState().position(positionKey);
            if (current != null && current.signedQuantitySteps() != 0) continue;
            String symbol = owner.identities().symbol(previous.symbolId());
            var previousMarginMode = previous.marginMode();
            for (long triggerOrderId : owner.triggerOrderIndex().ids(previous.userId())) {
                var trigger = owner.runtimeState().triggerOrder(triggerOrderId);
                if (trigger == null || trigger.status()
                        != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                if (!symbol.equals(trigger.symbol()) || trigger.positionSide() != previous.positionSide()
                        || trigger.marginMode() != previousMarginMode) continue;
                selected.accept(triggerOrderId);
            }
        }
    }

    long requireTriggerOwner(long triggerOrderId) {
        var trigger = owner.runtimeState().triggerOrder(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        return trigger.userId();
    }
}
