package com.surprising.aeron.service.trigger.command;

import com.surprising.aeron.service.order.*;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.InstrumentId;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreOrderSide;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.CoreTriggerOrderType;
import com.surprising.aeron.protocol.PlaceOrderCommand;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.core.index.TriggerOrderIndex;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.support.collection.PrimitiveLongChangeSet;
import java.util.UUID;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.experimental.Accessors;

/** 触发业务命令及续步入口：条件登记后由价格预算扫描选候选，OCO、追踪、过期和重试沿用原状态转换，再把子单交现有准入、撮合和结算。Owner 管页与交接，账户 Lane 改触发和资金，必要的市场未平仓量由唯一账本读取。 */
public final class TriggerOrderCommands {
    /** 直接控制槽使用的触发单变更种类，各项对应原触发状态转换。 */
    public enum Mutation {
        CANCEL,
        CLAIM,
        COMPLETE,
        TRAILING,
        EXPIRE,
        /** 按陈旧界限恢复允许重试的触发状态。 */
        RETRY
    }

    /** 本产品线交易 Owner，串行推进本类命令并管理提交边界。 */
    private final TradingCoreRuntime owner;

    /** Owner 复用的已平仓关联触发 ID 缓冲，后续复制入既有提交事件，下一命令前可重用。借出 Owner 当前复用的清仓触发单 ID 缓冲，调用方在下次清空或重新选择前消费。 */
    @Accessors(fluent = true, chain = false)
    @Getter
    private final PrimitiveLongChangeSet closingTriggerIds = new PrimitiveLongChangeSet();
    private static final java.util.function.BooleanSupplier COMPLETE = () -> true;
    /** Owner 串行触发扫描复用的一页续步，不为每次价格更新新建整个捕获图。 */
    private final PendingTriggerScan reusableTriggerScan = new PendingTriggerScan();
    /** 触发子单串行执行复用的账户操作与 Owner 续步。 */
    private final TriggerExecutionContinuation reusableTriggerExecution = new TriggerExecutionContinuation();

    /** 清缓冲后选出本命令已平仓对应的待触发单，核对各单取消修订可增加，再检查全局修订加总不溢出。 */
    public PrimitiveLongChangeSet collectClosingTriggerIds() {
        closingTriggerIds.clear();
        selectClosingTriggers(id -> {
            if (!owner.triggerOrderIndex().route(id).cancelRevisionAvailable())
                throw new ArithmeticException("trigger cancellation revision overflow");
            closingTriggerIds.add(id);
        });
        Math.addExact(owner.runtimeState().revision(), closingTriggerIds.size());
        return closingTriggerIds;
    }

    /** 保存非空交易 Owner，复用扫描与子单续步。本构造不启动任何工作线程。 */
    public TriggerOrderCommands(TradingCoreRuntime owner) {
        this.owner = java.util.Objects.requireNonNull(owner);
    }

    /** 仅在现有风险扫描与本价格序列一致时，固定当前最大待触发 ID、标记价及报价时间，重置触发阶段和 OCO 续步。 */
    public void initializeTriggerScan(com.surprising.aeron.protocol.ApplyMarkPriceCommand command) {
        Integer symbolId = owner.identities().findSymbolId(command.instrumentId());
        RiskScanner.State scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.priceSequence() != command.priceSequence()) return;
        long upperId = owner.triggerOrderIndex().maxPendingId(command.instrumentId());
        owner.runtimeState().replaceRiskScan(
                scan.withTriggerProgress(upperId == 0, TriggerOrderIndex.PHASE_GREATER_OR_EQUAL,
                Long.MAX_VALUE, Long.MAX_VALUE, upperId, command.markPriceTicks(),
                command.generatedAtEpochMillis()).withTriggerOcoProgress(0, 0));
    }

    /** 没有扫描或触发部分已完成时返回共享完成回调。否则准备一页可续步工作，页预算取请求与配置的较小者。 */
    public java.util.function.BooleanSupplier pendingTriggerScan(String instrumentId, int maxWork) {
        Integer symbolId = owner.identities().findSymbolId(instrumentId);
        RiskScanner.State scan = symbolId == null ? null : owner.runtimeState().riskScan(symbolId);
        if (scan == null || scan.triggerComplete()) return COMPLETE;
        return reusableTriggerScan.prepare(instrumentId, scan,
                Math.min(maxWork, owner.defaultTriggerScanBatchSize()));
    }

    /** 返回空新值时仍回退 Owner 已发布索引，本方法不单独区分删除与没有新值。 */
    private TriggerOrderIndex.TriggerRoute currentTriggerRoute(long id) {
        var receipt = owner.runtimeState().changedTriggerOrder(id);
        return receipt == null ? owner.triggerOrderIndex().route(id) : TriggerOrderIndex.TriggerRoute.from(receipt);
    }

    /** Owner 串行复用的一页触发扫描续步，保留价格与 OCO 游标并等待账户变更或子单准入。构造约定：创建供 Owner 串行复用的页续步，具体输入由 prepare 填入。 */
    @NoArgsConstructor(access = AccessLevel.PACKAGE)
    private final class PendingTriggerScan implements java.util.function.BooleanSupplier {
        private int instrumentId;
        private UUID commandId;
        private RiskScanner.State scan;
        private int remaining;
        private TriggerOrderIndex.TriggerCandidatePage page;
        private int candidate;
        private boolean ocoComplete, finished;
        private java.util.function.BooleanSupplier pending;
        private final ScanMutation reusableMutation = new ScanMutation();

        /** 固定标的整数身份、扫描值和页预算，用产品线、标的文本及价格序列合成可重建命令 UUID，清上次页和等待状态后返回自身。 */
        PendingTriggerScan prepare(String instrumentId, RiskScanner.State scan, int remaining) {
            this.instrumentId = com.surprising.product.api.utils.InstrumentIds.parse(instrumentId);
            this.scan = scan;
            this.remaining = remaining;
            long msb = ((long) owner.productLine().ordinal() << 48)
                    ^ (instrumentId == null ? 0L : ((long) instrumentId.hashCode() << 16))
                    ^ 0x4d41524b50524943L;
            long lsb = scan.priceSequence();
            commandId = new UUID(msb, lsb);
            candidate = 0;
            page = null;
            pending = null;
            ocoComplete = false;
            finished = false;
            return this;
        }

        /** 先续完已派发工作，优先恢复 OCO 撤单，再取当前价格来源的一页候选。候选页处理完就保存下一阶段或游标并结束本次工作，账户未完成则返回假让 Owner 稍后再轮询。 */
        @Override public boolean getAsBoolean() {
            for (;;) {
                if (pending != null) {
                    if (!pending.getAsBoolean()) return false;
                    pending = null;
                }
                if (finished) return true;
                if (page == null) {
                    if (scan.triggerOcoOrderId() != 0) {
                        var trigger = currentTriggerRoute(scan.triggerOcoOrderId());
                        if (trigger != null && trigger.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                            cancelSiblings(trigger, scan.triggerOcoCursor() == 0 ? Long.MAX_VALUE : scan.triggerOcoCursor(), true);
                            continue;
                        }
                        scan = scan.withTriggerOcoProgress(0, 0);
                        replaceRiskScan(scan);
                    }
                    if (remaining <= 0) return true;
                    int sourceIndex = scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE;
                    var source = com.surprising.aeron.protocol.CoreTriggerPriceSource.values()[sourceIndex];
                    var mark = owner.runtimeState().markPrice(scan.symbolId());
                    long sourcePrice = source == com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK
                            ? scan.triggerMarkPriceTicks()
                            : mark == null || mark.priceSequence() != scan.priceSequence() ? 0
                            : source == com.surprising.aeron.protocol.CoreTriggerPriceSource.LAST
                                    ? mark.lastPriceTicks() : mark.indexPriceTicks();
                    page = owner.triggerOrderIndex().candidatesPage(instrumentId, source, sourcePrice,
                            scan.triggerPhase() % TriggerOrderIndex.PHASE_COMPLETE,
                            scan.triggerPriceCursor(), scan.triggerOrderCursor(), scan.triggerUpperId(), remaining);
                }
                if (candidate == page.ids().size()) {
                    int nextPhase = page.complete()
                            ? ((scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE) + 1)
                                    * TriggerOrderIndex.PHASE_COMPLETE
                            : (scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE)
                                    * TriggerOrderIndex.PHASE_COMPLETE + page.nextPhase();
                    replaceRiskScan(scan.withTriggerProgress(nextPhase == 3 * TriggerOrderIndex.PHASE_COMPLETE,
                            nextPhase, page.complete() ? Long.MAX_VALUE : page.nextPriceCursor(),
                            page.complete() ? Long.MAX_VALUE : page.nextOrderCursor(),
                            scan.triggerUpperId(), scan.triggerMarkPriceTicks(),
                            scan.triggerGeneratedAtEpochMillis()).withTriggerOcoProgress(0, 0));
                    finished = true;
                    return true;
                }
                long id = page.idAt(candidate);
                var trigger = currentTriggerRoute(id);
                if (trigger == null || trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) {
                    candidate++;
                    continue;
                }
                var source = com.surprising.aeron.protocol.CoreTriggerPriceSource.values()[
                        scan.triggerPhase() / TriggerOrderIndex.PHASE_COMPLETE];
                var mark = owner.runtimeState().markPrice(scan.symbolId());
                long price = source == com.surprising.aeron.protocol.CoreTriggerPriceSource.MARK
                        ? scan.triggerMarkPriceTicks()
                        : source == com.surprising.aeron.protocol.CoreTriggerPriceSource.LAST
                                ? mark.lastPriceTicks() : mark.indexPriceTicks();
                long at = scan.triggerGeneratedAtEpochMillis();
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
                pending = beginTriggerExecution(id, scan.priceSequence(), price, at, commandId, false);
            }
        }

        /** 从稳定 Owner 索引借用同组倒序 ID，只选排除当前单且小于游标的一段，按剩余预算定位下一界。 */
        private void cancelSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                    long cursor, boolean resumed) {
            var siblings = owner.triggerOrderIndex().ocoSiblingsDescending(trigger);
            int count = 0;
            long last = cursor;
            boolean more = false;
            for (var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                long id = siblingIds.nextLong();
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
            pending = reusableMutation;
            owner.runtimeState().dispatchControlLanes(
                    1L << owner.runtimeState().topology().accountLaneId(userId), reusableMutation);
        }

        /** 扫描页复用的账户任务：同一对象分别承担 Lane 操作和 Owner 完成轮询，使用前明确准备一种任务。 */
        private final class ScanMutation implements java.util.function.BooleanSupplier,
                java.util.function.IntFunction<Object> {
            private static final byte EXPIRE = 1, TRAILING = 2, SIBLINGS = 3;
            private byte kind;
            private long userId, triggerId, arg1, arg2, arg3, cursor, next;
            private int work;
            /** resumed：本次 OCO 页是否从保存的扫描续步恢复，而非当前候选首次检查。 */
            private boolean complete, resumed;
            private TriggerOrderIndex.TriggerRoute trigger;
            /** 借用 Owner 索引的稳定同组倒序集合，下次 prepare 前清理，不在任务准备时复制。 */
            private it.unimi.dsi.fastutil.longs.LongSortedSet siblings;

            /** 清上次任务数据，保存过期类型、触发单 ID 及过期检查时间。 */
            void prepareExpire(long triggerId, long at) {
                clear(); kind = EXPIRE; this.triggerId = triggerId; arg1 = at;
            }

            /** 清上次任务数据，保存追踪类型、触发单 ID 和高价、低价、激活时间。 */
            void prepareTrailing(long triggerId, long highest, long lowest, long activated) {
                clear(); kind = TRAILING; this.triggerId = triggerId;
                arg1 = highest; arg2 = lowest; arg3 = activated;
            }

            /** 清上次任务数据，借入稳定同组 ID 集和当前页范围、工作量及是否恢复标志。 */
            void prepareSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                 long cursor, long next, int work, boolean complete, boolean resumed,
                                 it.unimi.dsi.fastutil.longs.LongSortedSet siblings) {
                clear(); kind = SIBLINGS; this.trigger = trigger; this.cursor = cursor; this.next = next;
                this.work = work; this.complete = complete; this.resumed = resumed; this.siblings = siblings;
            }

            /** 没有实际账户工作时直接保存本页结果并推进扫描续步，不派发空 Lane 操作。 */
            void completeSiblings(TriggerOrderIndex.TriggerRoute trigger,
                                  long next, int work, boolean complete, boolean resumed) {
                this.trigger = trigger; this.next = next; this.work = work;
                this.complete = complete; this.resumed = resumed; finishSiblings();
            }

            private Object applyMutation() {
                return switch (kind) {
                    case EXPIRE -> owner.runtimeState().expireTriggerOrder(triggerId, arg1);
                    case TRAILING -> owner.runtimeState().updateTrailingTriggerOrder(triggerId,
                            arg1, arg2, arg3);
                    case SIBLINGS -> {
                        boolean changed = false;
                        for (var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                            long id = siblingIds.nextLong();
                            if (id == trigger.triggerOrderId() || id >= cursor) continue;
                            if (id < next) break;
                            var sibling = owner.runtimeState().triggerOrder(id);
                            if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                                changed |= owner.runtimeState().cancelTriggerOrder(
                                        trigger.userId(), id);
                        }
                        yield changed;
                    }
                    default -> throw new IllegalStateException("unknown trigger scan mutation");
                };
            }

            /** 未撤完保存目标单和下一游标并结束本页，恢复段撤完则清 OCO 游标，普通段撤完则允许当前候选执行。 */
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

            /** 下次准备前清数值、标志和借用的触发路由与同组集合。 */
            void clear() {
                kind = 0; userId = triggerId = arg1 = arg2 = arg3 = cursor = next = 0;
                work = 0; complete = resumed = false; trigger = null; siblings = null;
            }

            /** 账户 Lane 回调统一执行本次变更。 */
            @Override public Object apply(int ignoredLaneId) { return applyMutation(); }

            /** Owner 等待控制 Lane 结果，未齐返回假。 */
            @Override public boolean getAsBoolean() {
                if (!owner.runtimeState().pollControlLanes()) return false;
                Object result = owner.runtimeState().controlLaneResult(
                        owner.runtimeState().topology().accountLaneId(userId));
                if (Boolean.TRUE.equals(result)) owner.requestCommitPublication();
                if (kind == SIBLINGS) finishSiblings();
                return true;
            }
        }
    }

    /** 把触发扫描游标与 OCO 进度接回原风险状态转换，然后请求命令提交发布。 */
    void replaceRiskScan(RiskScanner.State scan) {
        owner.runtimeState().replaceRiskScan(scan);
        owner.requestCommitPublication();
    }

    /** 基价或回调率非正返回零。否则精确相乘后按百万分之一向下取整得到追踪回撤价差，中间乘法溢出明确失败。 */
    static long trailingDelta(long base, long callbackRatePpm) {
        if (base <= 0 || callbackRatePpm <= 0) return 0;
        return Math.floorDiv(Math.multiplyExact(base, callbackRatePpm), 1_000_000L);
    }

    /** 从完整触发单判断条件：普通单比较固定阈值，追踪卖单按最高价回落、买单按最低价反弹。 */
    static boolean isTriggerConditionSatisfied(
            com.surprising.aeron.model.CoreTriggerOrderState trigger, long priceTicks) {
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

    /** 只用 Owner 路由判断同一套普通或追踪价格条件，供扫描筛选。 */
    static boolean isTriggerConditionSatisfied(
            TriggerOrderIndex.TriggerRoute trigger, long priceTicks) {
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

    private com.surprising.aeron.model.CoreTriggerOrderState executableTrigger(
            com.surprising.aeron.model.CoreTriggerOrderState trigger, long sequence, long price, Integer symbolId) {
        if (trigger == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (trigger.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) return null;
        var mark = symbolId == null ? null : owner.runtimeState().markPrice(symbolId);
        long selectedPrice = mark == null ? 0 : switch (trigger.priceSource()) {
            case MARK -> mark.markPriceTicks();
            case LAST -> mark.lastPriceTicks();
            case INDEX -> mark.indexPriceTicks();
        };
        if (mark == null || selectedPrice <= 0 || mark.priceSequence() != sequence
                || selectedPrice != price || !isTriggerConditionSatisfied(trigger, price))
            throw new CoreStateRejectedException("TRIGGER_CONDITION_NOT_MET", "trigger price is not executable");
        return trigger;
    }

    private PlaceOrderCommand childOrder(com.surprising.aeron.model.CoreTriggerOrderState trigger,
            long price, com.surprising.aeron.service.instrument.CoreInstrument instrument, long orderId) {
        long limit = trigger.orderType() == com.surprising.aeron.protocol.CoreOrderType.LIMIT
                ? (trigger.priceTicks() > 0 ? trigger.priceTicks() : price) : 0;
        return new PlaceOrderCommand(orderId,
                trigger.instrumentId(), trigger.side(), limit, trigger.quantitySteps(),
                instrument.contractType() != com.surprising.instrument.api.model.ContractType.SPOT,
                trigger.marginMode(), trigger.positionSide(), trigger.orderType(), trigger.timeInForce(), false,
                "TRIGGER:" + trigger.triggerOrderId());
    }

    /** 要求同用户、产品、币对、方向、资金模式及数量，组标识相同且非空，订单和客户编号不同。 */
    public static void validateOcoPair(CoreTriggerOrderStateView first, CoreTriggerOrderStateView second) {
        if (first == null || second == null || first.ocoGroupId().isBlank()
                || !first.ocoGroupId().equals(second.ocoGroupId()) || first.userId() != second.userId()
                || first.productLine() != second.productLine() || !first.instrumentId().equals(second.instrumentId())
                || first.side() != second.side() || first.marginMode() != second.marginMode()
                || first.positionSide() != second.positionSide() || first.quantitySteps() != second.quantitySteps()
                || first.triggerOrderId() == second.triggerOrderId()
                || first.clientTriggerOrderId().equals(second.clientTriggerOrderId())
                || first.triggerType() != CoreTriggerOrderType.TAKE_PROFIT
                || second.triggerType() != CoreTriggerOrderType.STOP_LOSS
                || (first.side() == com.surprising.aeron.protocol.CoreOrderSide.SELL
                    ? first.triggerPriceTicks() <= second.triggerPriceTicks()
                    : first.triggerPriceTicks() >= second.triggerPriceTicks())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "invalid take-profit / stop-loss OCO pair");
        }
    }

    /** 按当前产品线、用户和触发视图，通过原身份表准备对应持仓键。 */
    long preparedTriggerPositionKey(
            long userId, com.surprising.aeron.protocol.CoreTriggerOrderStateView trigger) {
        if (!owner.productLine().isDerivative()) return 0;
        return owner.identities().positionKey(userId,
                InstrumentId.requireValid(trigger.instrumentId()),
                trigger.positionSide());
    }

    private long triggerChildOrderId(long triggerOrderId) {
        long candidate = ((triggerOrderId << 1) | 1L) & Long.MAX_VALUE;
        long first = candidate;
        while (owner.orderIdInUse(candidate)) {
            candidate = candidate == Long.MAX_VALUE ? 1 : candidate + 2;
            if (candidate == first) throw new CoreStateRejectedException("INVALID_COMMAND", "trigger child order IDs exhausted");
        }
        return candidate;
    }

    /** 解码并用业务时间物化算法视图，异步路径另核用户并准备标的索引，交原控制槽，冷路径直接更新并请求发布。 */
    public void executeUpsertAlgoOrder(CoreMessage message, long clusterTimestamp) {
        var algo = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeCommand(message.payloadUnsafe());
        var current = owner.runtimeState().algoRoute(algo.algoOrderId());
        long userId = message.header().userId();
        if (current != null && current.userId() != userId) {
            throw new CoreStateRejectedException("ALGO_ORDER_OWNER_MISMATCH", "algo order belongs to another user");
        }
        int symbolId = -1;
        if (algo instanceof com.surprising.aeron.protocol.CoreAlgoOrderCommand.Create create) {
            symbolId = owner.identities().symbolId(create.instrumentId());
        } else if (current != null) symbolId = owner.identities().symbolId(current.instrumentId());
        owner.directCommand.deferAlgoUpsertControl(userId, algo, symbolId, clusterTimestamp);
    }

    /** 解码延时全撤控制并应用原用户状态转换，正常返回后请求发布。 */
    public void executeUpdateCancelAllAfter(CoreMessage message) {
        owner.runtimeState().updateCancelAllAfter(message.header().userId(),
                com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeCommand(message.payloadUnsafe()));
        owner.requestCommitPublication();
    }

    /** 要求恰好两条并按业务时间建立视图，核对配对和交易开关，再准备标的、持仓键和已结算标记。 */
    public void executePlaceTriggerOcoPair(CoreMessage message, long clusterTimestamp) {
        var views = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodePlacementList(message.payloadUnsafe());
        if (views.size() != 2) throw new CoreStateRejectedException("INVALID_COMMAND", "OCO requires two legs");
        var first = views.get(0).materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        var second = views.get(1).materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        validateOcoPair(first, second);
        var instrument = owner.runtimeState().instrument(first.instrumentId());
        if (instrument != null) {
            instrument.requireOrderEnabled(first.orderType(), first.timeInForce(), false, false);
            instrument.requireOrderEnabled(second.orderType(), second.timeInForce(), false, false);
        }
        int symbolId = owner.identities().symbolId(first.instrumentId());
        long positionKey = preparedTriggerPositionKey(message.header().userId(), first);
        boolean settled = owner.runtimeState().treasury().lifecycleSettlement(symbolId) != 0;
        owner.directCommand.deferTriggerOcoPairControl(message.header().userId(), first, second, symbolId, positionKey, settled);
    }

    /** 解码并建立带业务时间的视图，检查标的开关，再准备标的与持仓输入。 */
    public void executePlaceTriggerOrder(CoreMessage message, long clusterTimestamp) {
        var trigger = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodePlacement(message.payloadUnsafe())
                .materialize(message.header().productLine(), message.header().userId(), clusterTimestamp);
        var maintenanceInstrument = owner.runtimeState().instrument(trigger.instrumentId());
        if (maintenanceInstrument != null) {
            maintenanceInstrument.requireOrderEnabled(trigger.orderType(), trigger.timeInForce(), false, false);
        }
        int symbolId = owner.identities().symbolId(trigger.instrumentId());
        long positionKey = preparedTriggerPositionKey(message.header().userId(), trigger);
        boolean instrumentSettled = owner.runtimeState().treasury().lifecycleSettlement(symbolId) != 0;
        owner.directCommand.deferTriggerUpsertControl(message.header().userId(), trigger, symbolId, positionKey,
                instrumentSettled);
    }

    /** 解码触发 ID，交取消类型控制任务异步处理。 */
    public void executeCancelTriggerOrder(CoreMessage message) {
        long triggerOrderId = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeId(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(message.header().userId(), Mutation.CANCEL,
                triggerOrderId, 0, 0, 0, false, null);
    }

    /** 解码认领参数，按触发单真实所属用户交异步控制任务。 */
    public void executeClaimTriggerOrder(CoreMessage message) {
        long[] claim = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeClaim(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(claim[0]), Mutation.CLAIM,
                claim[0], claim[1], claim[2], claim[3], false, null);
    }

    /** 解码成功标记、子单 ID 和完成时间，按真实所属用户完成触发单。 */
    public void executeCompleteTriggerOrder(CoreMessage message) {
        long[] complete = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeComplete(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(complete[0]), Mutation.COMPLETE,
                complete[0], complete[2], complete[3], 0, complete[1] == 1, "");
    }

    /** 解码追踪高低价与激活时间，按真实用户交异步控制任务。 */
    public void executeUpdateTriggerTrailing(CoreMessage message) {
        long[] trailing = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeTrailing(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(trailing[0]), Mutation.TRAILING,
                trailing[0], trailing[1], trailing[2], trailing[3], false, null);
    }

    /** 解码触发 ID 与过期检查时间，按真实用户交过期控制任务。 */
    public void executeExpireTriggerOrder(CoreMessage message) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(lifecycle[0]), Mutation.EXPIRE,
                lifecycle[0], lifecycle[1], 0, 0, false, null);
    }

    /** 解码触发 ID 和陈旧界限，以请求头提交时间作为重试时间，按真实用户交重试控制任务。 */
    public void executeRetryTriggerOrder(CoreMessage message) {
        long[] lifecycle = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeLifecycle(message.payloadUnsafe());
        owner.directCommand.deferTriggerMutationControl(requireTriggerOwner(lifecycle[0]), Mutation.RETRY,
                lifecycle[0], lifecycle[1], message.header().submittedAtEpochMillis(), 0, false, null);
    }

    /** 显式执行请求：解码并把子单续步交直接控制槽等待。 */
    public void executeTriggerOrder(CoreMessage message) {
        long[] execute = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeExecute(message.payloadUnsafe());
        long triggerId = execute[0], triggerSequence = execute[1], price = execute[2], triggeredAt = execute[3];
        owner.directCommand.deferControl(beginTriggerExecution(triggerId, triggerSequence, price, triggeredAt,
                message.header().commandId(), true));
    }

    /** 在 Owner 索引中找原路由，不存在则拒绝，非待触发直接返回完成。 */
    private java.util.function.BooleanSupplier beginTriggerExecution(long triggerId, long triggerSequence,
            long price, long triggeredAt, UUID commandId, boolean cancelOco) {
        var route = owner.triggerOrderIndex().route(triggerId);
        if (route == null) throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order not found");
        if (route.status() != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) return COMPLETE;
        long childOrderId = triggerChildOrderId(triggerId);
        return reusableTriggerExecution.prepare(route, triggerId, triggerSequence, price, triggeredAt,
                commandId, cancelOco, childOrderId);
    }

    /** 触发子单准入的复用续步：必要时先查询唯一账本，再派发所属账户 Lane 完成触发认领和冻结，Owner 收集回执后交撮合。 */
    private final class TriggerExecutionContinuation
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 账户 Lane 复核并认领后的完整触发单，Owner 收集回执后清引用。 */
        private com.surprising.aeron.model.CoreTriggerOrderState trigger;
        /** childSequence：子单准入及后续撮合使用的预先算定 Core 顺序。childOrderId：Owner 在派发账户前选定的未占用正奇数子订单 ID。 */
        private long triggerId, triggerSequence, price, triggeredAt, childSequence, childOrderId;
        private UUID commandId;
        /** 触发单所属用户账户 Lane，由 Owner 预先定位。 */
        private int laneId;
        private int baseAssetId, quoteAssetId, settleAssetId;
        /** Owner 固定的订单决策上下文，账户 Lane 据此解析子单，交接完成后清引用。 */
        private com.surprising.aeron.service.order.OrderAdmissionService.Context decision;
        private long openInterest;
        /** 子单账户工作是否已派发，防止 Owner 轮询时重复派同一任务。 */
        private boolean accountDispatched;
        /** 本次可选借入的同组倒序集合，准备时固定来源、Owner 收集后清引用。 */
        private it.unimi.dsi.fastutil.longs.LongSortedSet siblings;

        /** 固定子单 ID 和本次触发输入，准备订单决策、资产整数身份及原命令序号。 */
        java.util.function.BooleanSupplier prepare(TriggerOrderIndex.TriggerRoute route,
                long triggerId, long triggerSequence, long price, long triggeredAt, UUID commandId,
                boolean cancelOco, long childOrderId) {
            this.childOrderId = childOrderId;
            trigger = null;
            this.triggerId = triggerId; this.triggerSequence = triggerSequence;
            this.price = price; this.triggeredAt = triggeredAt; this.commandId = commandId;
            decision = com.surprising.aeron.service.order.OrderAdmissionService.context(
                    owner.runtimeState(), owner.identities(), route.instrumentId(), owner.currentClusterTimestamp());
            baseAssetId = owner.identities().assetId(decision.instrument().baseAsset());
            quoteAssetId = owner.identities().assetId(decision.instrument().quoteAsset());
            settleAssetId = owner.identities().assetId(decision.instrument().settleAsset());
            if (decision.instrument().usesOpenInterestForUserLimit()) {
                openInterest = owner.runtimeState().ledgerAdmissionOpenInterest(decision.instrument().instrumentId());
            } else {
                openInterest = 0;
            }
            accountDispatched = false;
            childSequence = Math.addExact(Math.addExact(owner.appliedCommandCount(), 2), owner.queuedMatchingCount());
            siblings = cancelOco ? owner.triggerOrderIndex().ocoSiblingsDescending(route)
                    : it.unimi.dsi.fastutil.longs.LongSortedSets.EMPTY_SET;
            laneId = owner.runtimeState().topology().accountLaneId(route.userId());
            return this;
        }

        /** 账户 Lane 先用完整触发单复核行情及条件，解析子单准入。技术异常继续传播，不扩大为本方法回滚所有业务状态。 */
        @Override public Object apply(int ignoredLaneId) {
            trigger = executableTrigger(owner.runtimeState().triggerOrder(triggerId), triggerSequence, price, decision.symbolId());
            if (trigger == null) return null;
            com.surprising.aeron.service.order.OrderCommand.ResolvedPlaceOrder order = null;
            String failureReason = "";
            try {
                order = com.surprising.aeron.service.order.OrderAdmissionService.resolve(
                        decision, trigger.userId(), childOrder(trigger, price, trigger.instrument(), childOrderId));
            } catch (CoreStateRejectedException rejected) { failureReason = rejected.code(); }
            int assetId = order == null ? 0 : order.reservationAsset().equals(decision.instrument().baseAsset()) ? baseAssetId
                    : order.reservationAsset().equals(decision.instrument().quoteAsset()) ? quoteAssetId : settleAssetId;
            for (var siblingIds = siblings.iterator(); siblingIds.hasNext();) {
                long siblingId = siblingIds.nextLong();
                if (siblingId == triggerId) continue;
                var sibling = owner.runtimeState().triggerOrder(siblingId);
                if (sibling != null && sibling.status() == com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING)
                    owner.runtimeState().cancelTriggerOrder(trigger.userId(), siblingId);
            }
            owner.runtimeState().claimTriggerOrder(triggerId, triggerSequence, price, triggeredAt);
            trigger = owner.runtimeState().triggerOrder(triggerId);
            if (!failureReason.isEmpty()) {
                owner.runtimeState().completeTriggerOrder(triggerId, false, 0,
                        failureReason, triggeredAt);
                return null;
            }
            var key = owner.identities().prepareClientKeyInCurrentLane(trigger.userId(), order.clientOrderId());
            try {
                var receipt = owner.runtimeState().placeTriggerChildInLane(trigger.userId(), order,
                        commandId, childSequence, openInterest, owner.identities(),
                        com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.clientKeyValue(key), assetId,
                        decision.lifecycleSettled(), decision.fundingInProgress());
                return receipt;
            } catch (CoreStateRejectedException rejected) {
                if (key != 0) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                owner.runtimeState().completeTriggerOrder(triggerId, false, 0,
                        rejected.code(), triggeredAt);
                return null;
            } catch (RuntimeException | Error failure) {
                if (key != 0) owner.identities().rollbackClientKeyInCurrentLane(trigger.userId(),
                        order.clientOrderId(), key);
                throw failure;
            }
        }

        /** Owner 派发一次账户工作。 */
        @Override public boolean getAsBoolean() {
            if (!accountDispatched) {
                owner.runtimeState().dispatchControlLanes(1L << laneId, this);
                accountDispatched = true;
            }
            if (!owner.runtimeState().pollControlLanes()) return false;
            var result = owner.runtimeState().controlLaneResult(laneId);
            owner.requestCommitPublication();
            if (result != null) {
                var receipt = (com.surprising.aeron.service.order.Order) result;
                owner.runtimeState().pendingReservations().collectControlReservation(receipt, childSequence);
                owner.resultBuilder.markUserChanged(trigger.userId());
                owner.resultBuilder.markOrderChanged(receipt.orderId());
                owner.queueTriggerMatching(trigger, triggerSequence, price, triggeredAt, commandId, receipt);
            }
            trigger = null;
            decision = null;
            commandId = null;
            siblings = null;
            return true;
        }
    }

    /** 从该用户 Owner 索引筛选相同标的、持仓方向及保证金模式的待触发单，交调用方消费。 */
    private void selectClosingTriggers(java.util.function.LongConsumer selected) {
        owner.seedChangeAccumulators();
        if (!owner.runtimeState().hasChangedPositions()) return;
        org.eclipse.collections.api.iterator.LongIterator changedPositions =
                owner.runtimeState().changedPositionIterator();
        while (changedPositions.hasNext()) {
            long positionKey = changedPositions.next();
            var previous = owner.runtimeState().currentPatchPositionBefore(positionKey);
            if (previous == null || previous.signedQuantitySteps() == 0) continue;
            var current = owner.runtimeState().changedPositionValue(positionKey);
            if (current != null && current.signedQuantitySteps() != 0) continue;
            int instrumentId = previous.instrument().instrumentIdValue();
            var previousMarginMode = previous.marginMode();
            for (var triggerIds = owner.triggerOrderIndex().ids(previous.userId()).iterator(); triggerIds.hasNext();) {
                long triggerOrderId = triggerIds.nextLong();
                var trigger = currentTriggerRoute(triggerOrderId);
                if (trigger == null || trigger.status()
                        != com.surprising.aeron.protocol.CoreTriggerOrderStatus.PENDING) continue;
                if (instrumentId != trigger.instrumentId() || trigger.positionSide() != previous.positionSide()
                        || trigger.marginMode() != previousMarginMode) continue;
                selected.accept(triggerOrderId);
            }
        }
    }

    /** 按本命令新路由优先、已发布索引次之查所属用户，找不到触发单则明确拒绝。 */
    long requireTriggerOwner(long triggerOrderId) {
        var trigger = currentTriggerRoute(triggerOrderId);
        if (trigger == null) {
            throw new CoreStateRejectedException("TRIGGER_ORDER_NOT_FOUND", "trigger order does not exist");
        }
        return trigger.userId();
    }
}
