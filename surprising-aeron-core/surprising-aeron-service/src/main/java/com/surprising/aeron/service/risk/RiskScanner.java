package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.model.RiskLaneProgress;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.protocol.CorePositionSide;
import com.surprising.aeron.service.account.AccountBalance;
import com.surprising.aeron.service.account.UserAccount;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.core.index.PositionUserIndex;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.service.order.Order;
import com.surprising.aeron.service.order.OrderFundsReservation;
import com.surprising.aeron.service.position.Position;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.math.PerpetualContractMath;
import java.util.ArrayList;
import java.util.List;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.experimental.Accessors;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

public final class RiskScanner {
    private final TradingAccountRuntime runtime;
    /** 本命令共用的业务编号映射，Owner 准备路由和 Lane 估值时使用。 */
    private final RuntimeIdentityRegistry identities;
    private final PositionUserIndex positionUsers;
    private int budget;
    /** 本命令尚未消耗的工作预算，Owner 每片完成后扣减。 */
    private int remaining;
    private final RiskLaneProgress[] inputs;
    /** Owner 按 Lane 编号收集的估值回执，后续用于编号和合并游标。 */
    private final Page[] results;
    private final int[] allocations;
    private final org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[] userPages;
    private int sliceBudget;
    /** Owner 选中的本片原扫描记录，含行情序号及各 Lane 上次进度。 */
    private RiskScanner.State initial;
    /** Owner 捕获的币对配置引用，Lane 估值只读使用，不在这里深复制配置。 */
    private CoreInstrument instrument;
    /** Owner 捕获且与扫描序号一致的行情记录，交 Lane 估值。 */
    private MarkPrice mark;
    private int settleAssetId;
    /** laneMask：账本准备的参与估值 Lane 位集合，Owner 在路由发布后据此派发。creationMask：Owner 根据估值回执整理的新增强平写入 Lane 位集合。nextLiquidationId：Owner 给当前片段分配完需求后得到的下一强平编号。 */
    private long laneMask, creationMask, nextLiquidationId;
    /** nextLane：下一轮从哪个 Lane 开始分配预算，Owner 在本片结束时提交为轮转位置。work：当前片段实际扣减的预算，参与 Lane 或空访问至少各计一个单位。 */
    private int nextLane, work;
    private int phase;

    /** 创建使用持仓用户索引的风险续扫对象，初始化一次各 Lane 缓冲区，再按本命令设置预算。 */
    public RiskScanner(int maxWork, PositionUserIndex positionUsers, TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities) {
        if (maxWork <= 0 || maxWork > 4096 || positionUsers == null
                || runtime == null || identities == null) throw new IllegalArgumentException("invalid risk scan budget");
        runtime.assertOwner();
        this.runtime = runtime;
        this.identities = identities;
        this.positionUsers = positionUsers;
        int lanes = runtime.topology().accountLaneCount();
        inputs = new RiskLaneProgress[lanes];
        results = new Page[lanes];
        allocations = new int[lanes];
        userPages = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList[lanes];
        for (int lane = 0; lane < lanes; lane++) userPages[lane] = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList(9);
        resetForCommand(maxWork);
    }

    /** Owner 为下一命令重新设置预算和阶段，复用各 Lane 数组，丢弃旧命令完成或失败回滚后的残留片段。 */
    public void resetForCommand(int maxWork) {
        runtime.assertOwner();
        if (maxWork <= 0 || maxWork > 4096) {
            throw new IllegalArgumentException("invalid risk scan budget");
        }
        // 直接命令槽由 Owner 串行复用。
        // 续步失败且 Lane 工作已回滚后， 这里可能仍停在阶段 1 或 2。
        // 下一命令重置时必须丢弃旧页和旧输入。
        budget = runtime.riskScanControl().enabled()
                ? Math.min(maxWork, adaptiveBudget(runtime.riskScanControl().scanBatchSize(),
                        runtime.incompleteRiskScanCount())) : 0;
        remaining = budget;
        initial = null;
        instrument = null;
        mark = null;
        settleAssetId = 0;
        laneMask = creationMask = nextLiquidationId = 0;
        nextLane = work = 0;
        phase = 0;
    }

    /** 取配置的最低工作量与待扫币对数乘 8 的较大值，再封顶到 4096，使积压币对增加时获得确定且有界的预算。 */
    public static int adaptiveBudget(int minimum, int pendingInstruments) {
        if (minimum < 1 || minimum > 4096 || pendingInstruments < 0)
            throw new IllegalArgumentException("invalid adaptive risk scan budget");
        return (int) Math.min(4096L, Math.max(minimum, pendingInstruments * 8L));
    }

    /** Owner 非阻塞推进已有阶段：准备一片、准备用户页、派发并收集 Lane 估值、给新强平编号并等原 Lane 写入，最后提交游标。 */
    public boolean poll() {
        runtime.assertOwner();
        if (phase == 3) return true;
        if (phase == 0) {
            RiskScanner.State selected = remaining == 0 ? null : runtime.firstRiskIncompleteScan();
            if (selected == null) { phase = 3; return true; }
            prepare(selected, Math.min(8, remaining));
            runtime.publishRiskPositionInputs();
            prepareRoutes();
            if (laneMask != 0) runtime.dispatchRiskLanes(laneMask, lane ->
                    scan(runtime, lane, inputs[lane], userPages[lane],
                            instrument, mark, settleAssetId, initial.symbolId(), allocations[lane], identities));
            phase = 1;
        }
        if (laneMask != 0 && !runtime.pollControlLanes()) return false;
        if (phase == 1) {
            for (int lane = 0; lane < inputs.length; lane++)
                if ((laneMask & (1L << lane)) != 0)
                    results[lane] = (Page) runtime.controlLaneResult(lane);
            assignLiquidationIds();
            if (creationMask != 0) {
                runtime.dispatchRiskLanes(creationMask, lane -> {
                    results[lane].creations().apply(runtime);
                    return null;
                });
                phase = 2;
                return false;
            }
        }
        finish();
        runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
        remaining -= work;
        phase = remaining == 0 || runtime.firstRiskIncompleteScan() == null ? 3 : 0;
        return phase == 3;
    }

    /** 返回本命令已扣除的工作预算，空币对或空 Lane 的访问也至少计一个单位。 */
    public int completedWork() { return budget - remaining; }

    /** Owner 捕获所选币对当前扫描、配置、匹配序号的行情和结算资产，记录本片预算并重置路由发布标记。 */
    private void prepare(RiskScanner.State selected, int sliceBudget) {
        initial = selected;
        if (initial == null || initial.riskComplete() || sliceBudget <= 0)
            throw new IllegalStateException("risk scan is not pending");
        instrument = runtime.instrument(identities.instrumentId(initial.symbolId()));
        mark = runtime.markPrice(initial.symbolId());
        if (instrument == null || mark == null || mark.priceSequence() != initial.priceSequence())
            throw new IllegalStateException("risk scan input is missing");
        settleAssetId = identities.assetId(instrument.settleAsset());
        this.sliceBudget = sliceBudget;
    }

    /** 先发布持仓用户索引输入，再为每个 Lane 准备有界用户页与恢复游标，从上次轮转点给未完成 Lane 分配预算。 */
    private void prepareRoutes() {
        List<RiskLaneProgress> cursors = initial.laneProgress();
        if (!cursors.isEmpty() && cursors.size() != inputs.length)
            throw new IllegalStateException("risk scan Lane topology differs");
        laneMask = creationMask = 0;
        work = 0;
        int participants = 0;
        for (int lane = 0; lane < inputs.length; lane++) {
            RiskLaneProgress cursor = cursors.isEmpty()
                    ? new RiskLaneProgress(0, initial.riskComplete(), 0, 0, "-", 0, 0, 0, 0, 0)
                    : cursors.get(lane);
            var page = userPages[lane];
            page.clear();
            long next = positionUsers.higherUserId(instrument.instrumentId(), lane, cursor.lastUserId());
            for (int count = 0; next != 0 && count <= sliceBudget; count++) {
                page.add(next); next = positionUsers.higherUserId(instrument.instrumentId(), lane, next);
            }
            if (!cursor.complete() && cursor.userId() == 0 && page.isEmpty())
                cursor = new RiskLaneProgress(cursor.lastUserId(), true, 0, 0, "-", 0, 0, 0, 0, 0);
            inputs[lane] = cursor;
            results[lane] = null;
            allocations[lane] = 0;
            if (!cursor.complete()) participants++;
        }
        if (participants == 0) { nextLane = initial.accountLaneId(); return; }
        int selectedCount = Math.min(participants, sliceBudget);
        int assigned = 0;
        nextLane = initial.accountLaneId();
        for (int offset = 0; offset < inputs.length && assigned < selectedCount; offset++) {
            int lane = (initial.accountLaneId() + offset) % inputs.length;
            if (inputs[lane].complete()) continue;
            allocations[lane] = sliceBudget / selectedCount + (assigned < sliceBudget % selectedCount ? 1 : 0);
            laneMask |= 1L << lane;
            nextLane = (lane + 1) % inputs.length;
            assigned++;
        }
    }

    /** Owner 按 Lane 编号从小到大收集工作量，为新增强平需求分配连续编号，结果与线程完成先后无关。 */
    private void assignLiquidationIds() {
        nextLiquidationId = runtime.nextLiquidationId();
        if (laneMask == 0) work = 1;
        for (int lane = 0; lane < results.length; lane++) {
            if ((laneMask & (1L << lane)) == 0) continue;
            Page result = results[lane];
            work += Math.max(1, result.workUnits());
            if (result.creations().size() == 0) continue;
            nextLiquidationId = result.creations().assign(nextLiquidationId);
            creationMask |= 1L << lane;
        }
    }

    /** Owner 合并各 Lane 的游标和修订号，提交下一强平编号及轮转位置。 */
    private void finish() {
        List<RiskLaneProgress> cursors = new ArrayList<>(inputs.length);
        boolean complete = true;
        for (int lane = 0; lane < inputs.length; lane++) {
            Page page = results[lane];
            RiskLaneProgress cursor = page == null ? inputs[lane] : page.progress();
            cursors.add(cursor);
            complete &= cursor.complete();
        }
        if (nextLiquidationId != runtime.nextLiquidationId()) runtime.setNextLiquidationId(nextLiquidationId);
        if (complete) nextLane = inputs.length - 1;
        if (!complete) {
            while (cursors.get(nextLane).complete()) nextLane = (nextLane + 1) % inputs.length;
        }
        long scheduledRevision = Math.incrementExact(runtime.revision());
        RiskScanner.State next;
        if (complete && initial.scanStartPriceSequence() != initial.priceSequence()) {
            next = new RiskScanner.State(initial.symbolId(), 0, initial.priceSequence(), initial.priceSequence(),
                    false, initial.triggerComplete(), initial.triggerPhase(), initial.triggerPriceCursor(),
                    initial.triggerOrderCursor(), initial.triggerUpperId(), initial.triggerMarkPriceTicks(),
                    initial.triggerGeneratedAtEpochMillis(), 0, 0, scheduledRevision, List.of());
        } else {
            next = new RiskScanner.State(initial.symbolId(), nextLane, initial.priceSequence(),
                    initial.scanStartPriceSequence(), complete, initial.triggerComplete(), initial.triggerPhase(),
                    initial.triggerPriceCursor(), initial.triggerOrderCursor(), initial.triggerUpperId(),
                    initial.triggerMarkPriceTicks(), initial.triggerGeneratedAtEpochMillis(),
                    initial.triggerOcoOrderId(), initial.triggerOcoCursor(), scheduledRevision, cursors);
        }
        runtime.putRiskScan(next);
    }

    static Page scan(TradingAccountRuntime runtime, int laneId, RiskLaneProgress initial,
                                        LongArrayList indexedUserIds,
                                        CoreInstrument changedInstrument, MarkPrice changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RuntimeIdentityRegistry identities) {
        RiskScanner.LiquidationBatch creations = runtime.riskLiquidationBatch(maxWork);
        RiskLaneProgress progress = initial;
        int remaining = maxWork;
        long userRevision = initial.userRevision();
        long expectedMarketRevision = initial.marketRevision();
        while (remaining > 0) {
            UserAccount user = progress.userId() == 0
                    ? nextUser(runtime, indexedUserIds, progress.lastUserId())
                    : runtime.user(progress.userId());
            if (user == null) {
                return finishPage(progress, maxWork - remaining, true, creations);
            }
            if (progress.userId() == 0 || user.revision() != userRevision
                    || expectedMarketRevision != runtime.marketRevision()) {
                progress = new RiskLaneProgress(progress.lastUserId(), false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, user.revision(), runtime.marketRevision());
            }
            userRevision = user.revision();
            expectedMarketRevision = runtime.marketRevision();
            UserPage page = processUser(runtime, laneId, progress, user, changedInstrument, changedMark,
                    settleAssetId, changedSymbolId, remaining, creations, identities);
            progress = page.progress();
            int consumed = Math.max(1, page.workUnits());
            remaining -= consumed;
        }
        boolean complete = progress.userId() == 0
                && nextUser(runtime, indexedUserIds, progress.lastUserId()) == null;
        return finishPage(progress, maxWork - remaining, complete, creations);
    }

    private static UserPage processUser(TradingAccountRuntime runtime, int laneId,
                                        RiskLaneProgress scan, UserAccount user,
                                        CoreInstrument changedInstrument, MarkPrice changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RiskScanner.LiquidationBatch creations,
                                        RuntimeIdentityRegistry identities) {
        int phase = scan.phase();
        String positionCursor = scan.positionCursor();
        long reservationCursor = scan.reservationCursor();
        long unrealized = scan.unrealizedPnlUnits();
        long maintenance = scan.maintenanceMarginUnits();
        long isolatedMargin = scan.isolatedMarginUnits();
        long isolatedReservation = scan.isolatedReservationUnits();
        long wallet = Long.MIN_VALUE;
        PositionRiskScratch positionRisk = runtime.accountLane(laneId).positionRiskScratch();
        // 同一条常驻 Lane 线程可能在旧扫描失效后继续处理新价格。
        // 把小型行情缓存绑定到当前市场修订，避免继续使用旧标记价。
        positionRisk.scanMarketRevision = runtime.marketRevision();
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                long positionIdentity = runtime.nextRiskPositionKey(user.userId(), positionCursor, identities);
                if (positionIdentity == 0) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = identities.positionKey(user.userId(), positionIdentity);
                Position position = runtime.position(positionIdentity);
                work++;
                if (position.signedQuantitySteps() == 0) continue;
                if (position.marginMode() == CoreMarginMode.ISOLATED) {
                    if (position.assetId() == settleAssetId) {
                        isolatedMargin = Math.addExact(isolatedMargin, position.positionMarginUnits());
                    }
                    if (position.symbolId() == changedSymbolId) {
                        updateIsolated(runtime, user.userId(), positionCursor, position,
                                changedInstrument, changedMark, creations, identities);
                    }
                    continue;
                }
                if (position.assetId() != settleAssetId) continue;
                if (!risk(runtime, position, identities, positionRisk)) continue;
                unrealized = Math.addExact(unrealized, positionRisk.equityDelta);
                maintenance = Math.addExact(maintenance, positionRisk.maintenance);
                continue;
            }
            if (phase == 1) {
                OrderFundsReservation reservation = nextReservation(runtime, user.userId(), reservationCursor);
                if (reservation == null) {
                    if (maintenance == 0) {
                        return new UserPage(new RiskLaneProgress(user.userId(), false, 0, 0, "-", 0, 0, 0, 0, 0),
                                work);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = reservation.orderId();
                work++;
                Order order = runtime.order(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.assetId() == settleAssetId) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.reservedUnits());
                }
                continue;
            }
            long positionIdentity = runtime.nextRiskPositionKey(user.userId(), positionCursor, identities);
            if (positionIdentity == 0) {
                return new UserPage(new RiskLaneProgress(user.userId(), false, 0, 0, "-", 0, 0, 0, 0, 0),
                        work);
            }
            positionCursor = identities.positionKey(user.userId(), positionIdentity);
            Position position = runtime.position(positionIdentity);
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || position.assetId() != settleAssetId) continue;
            if (!risk(runtime, position, identities, positionRisk)) continue;
            if (wallet == Long.MIN_VALUE) {
                AccountBalance balance = runtime.balance(user.userId(), settleAssetId);
                long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
                wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
                if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            }
            long equity = Math.addExact(wallet, unrealized);
            long ratio = riskRatio(maintenance, equity);
            putRiskAndLiquidation(runtime, user.userId(), positionCursor, position,
                    positionRisk.instrument, positionRisk.priceSequence, equity, positionRisk.unrealized,
                    positionRisk.maintenance, ratio, creations, identities);
        }
        return new UserPage(new RiskLaneProgress(scan.lastUserId(), false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.userRevision(), scan.marketRevision()), work);
    }

    private static void updateIsolated(TradingAccountRuntime runtime, long userId, String positionKey,
                                       Position position, CoreInstrument instrument,
                                       MarkPrice mark, RiskScanner.LiquidationBatch creations,
                                       RuntimeIdentityRegistry identities) {
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks(), instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        long equity = Math.addExact(position.positionMarginUnits(), equityDelta);
        long ratio = riskRatio(maintenance, equity);
        putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, creations, identities);
    }

    private static void putRiskAndLiquidation(TradingAccountRuntime runtime, long userId, String positionKey,
                                              Position position, CoreInstrument instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, RiskScanner.LiquidationBatch creations,
                                              RuntimeIdentityRegistry identities) {
        CoreRiskStatus status = CoreRiskStatus.of(ratio);
        int symbolId = position.symbolId();
        long riskKey = identities.preparedPositionKey(userId, positionKey);
        RiskMarginCalculator.Snapshot previousSnapshot = runtime.riskSnapshot(riskKey);
        if (previousSnapshot == null || previousSnapshot.priceSequence() != priceSequence
                || previousSnapshot.equityUnits() != equity
                || previousSnapshot.unrealizedPnlUnits() != unrealized
                || previousSnapshot.maintenanceMarginUnits() != maintenance
                || previousSnapshot.marginRatioPpm() != ratio
                || previousSnapshot.positionSide() != position.positionSide()) {
            runtime.putRiskSnapshot(riskKey, new RiskMarginCalculator.Snapshot(userId, symbolId, position.positionSide(),
                    priceSequence, equity, unrealized, maintenance, ratio));
        }
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            return;
        }
        RiskScanner.LiquidationOrder active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION
                || !RiskMarginCalculator.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new RiskScanner.LiquidationOrder(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrument(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                long quantity = position.signedQuantitySteps();
                if (active.userId() != userId || active.symbolId() != symbolId
                        || active.marginMode() != position.marginMode()
                        || active.positionSide() != position.positionSide()
                        || active.instrument() != instrument
                        || active.triggerPriceSequence() != priceSequence
                        || active.signedQuantitySteps() != quantity
                        || active.closeQuantitySteps() != Math.absExact(quantity)) {
                    runtime.replaceLiquidation(new RiskScanner.LiquidationOrder(active.liquidationId(), userId, symbolId,
                            position.marginMode(), position.positionSide(), instrument, priceSequence,
                            quantity, Math.absExact(quantity), 0, 0, 0, 0,
                            CoreLiquidationState.Status.PLANNED, 0));
                }
            }
            return;
        }
        creations.add(position, priceSequence);
    }

    private static boolean risk(TradingAccountRuntime runtime, Position position,
                                RuntimeIdentityRegistry identities, PositionRiskScratch result) {
        int symbolId = position.symbolId();
        CoreInstrument instrument = result.instrument;
        MarkPrice mark = result.mark;
        if (instrument == null || mark == null || result.symbolId != symbolId
                || instrument != position.instrument()
                || result.marketRevision != result.scanMarketRevision
                || result.runtime != runtime) {
            instrument = runtime.instrument(identities.instrumentId(symbolId));
            mark = runtime.markPrice(symbolId);
            result.symbolId = symbolId;
            result.instrument = instrument;
            result.mark = mark;
            result.marketRevision = result.scanMarketRevision;
            result.runtime = runtime;
        }
        if (instrument == null || mark == null) return false;
        long unrealized = PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), mark.markPriceTicks(), instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
        result.instrument = instrument;
        result.priceSequence = mark.priceSequence();
        result.unrealized = unrealized;
        result.equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        result.maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        return true;
    }

    /** 不需维持保证金时为 0，权益不为正或中间乘法溢出时用最大 long 表示极高风险。 */
    private static long riskRatio(long maintenance, long equity) {
        if (maintenance <= 0) return 0;
        if (equity <= 0) return Long.MAX_VALUE;
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    private static UserAccount nextUser(TradingAccountRuntime runtime, LongArrayList users, long cursor) {
        int slot = users.binarySearch(cursor);
        slot = slot >= 0 ? slot + 1 : -slot - 1;
        return slot == users.size() ? null : runtime.user(users.get(slot));
    }

    private static OrderFundsReservation nextReservation(TradingAccountRuntime runtime, long userId, long cursor) {
        long id = runtime.nextRiskReservationId(userId, cursor);
        return id == 0 ? null : runtime.reservation(id);
    }

    /** 一个账户 Lane 复用的单持仓估值暂存区，处理下一持仓时覆盖。只缓存当前币对的配置和行情，跨市场修订或跨运行时不能沿用旧缓存。 */
    public static final class PositionRiskScratch {
        private CoreInstrument instrument;
        /** 缓存币对本次采用的行情引用，不能跨失效的市场修订继续用。 */
        private MarkPrice mark;
        private int symbolId = -1;
        private long marketRevision = Long.MIN_VALUE;
        private long scanMarketRevision = Long.MIN_VALUE;
        /** 缓存所对应的运行时引用，防止同一暂存区误用另一运行时的市场数据。 */
        private TradingAccountRuntime runtime;
        private long priceSequence;
        /** 当前持仓的未实现盈亏，单位为结算资产最小整数单位。 */
        private long unrealized;
        /** 当前持仓所需维持保证金，单位为结算资产最小整数单位。 */
        private long maintenance;
        private long equityDelta;
    }

    private static Page finishPage(RiskLaneProgress progress, int workUnits, boolean complete,
            RiskScanner.LiquidationBatch creations) {
        if (progress.complete() == complete) return new Page(progress, workUnits, creations);
        var result = new RiskLaneProgress(progress.lastUserId(), complete, progress.userId(), progress.phase(),
                progress.positionCursor(), progress.reservationCursor(), progress.unrealizedPnlUnits(),
                progress.maintenanceMarginUnits(), progress.isolatedMarginUnits(), progress.isolatedReservationUnits(),
                progress.userRevision(), progress.marketRevision());
        return new Page(result, workUnits, creations);
    }

    /** 一个账户 Lane 本批扫描的回执，Owner 收集后提交各 Lane 游标，并按固定顺序给新增强平需求分配编号。 */
    record Page(RiskLaneProgress progress, int workUnits, RiskScanner.LiquidationBatch creations) { }
    /** 单个用户在本批内的估值进度及工作量，用来区分用户已经算完与预算耗尽后还要继续两种情况。 */
    private record UserPage(RiskLaneProgress progress, int workUnits) { }

    public record State(
        int symbolId, int accountLaneId, long priceSequence, long scanStartPriceSequence,
        boolean riskComplete, boolean triggerComplete, int triggerPhase,
        long triggerPriceCursor, long triggerOrderCursor, long triggerUpperId,
        long triggerMarkPriceTicks, long triggerGeneratedAtEpochMillis,
        long triggerOcoOrderId, long triggerOcoCursor, long lastScheduledRevision,
        List<RiskLaneProgress> laneProgress) {
    public State {
        laneProgress = List.copyOf(laneProgress);
        if (laneProgress.size() > Long.SIZE || symbolId < 0 || accountLaneId < 0 || accountLaneId >= Long.SIZE
                || !laneProgress.isEmpty() && accountLaneId >= laneProgress.size()
                || priceSequence < 0 || scanStartPriceSequence < 0 || scanStartPriceSequence > priceSequence
                || triggerPhase < 0 || triggerPriceCursor < 0 || triggerOrderCursor < 0
                || triggerUpperId < 0 || triggerMarkPriceTicks < 0 || triggerGeneratedAtEpochMillis < 0
                || triggerOcoOrderId < 0 || triggerOcoCursor < 0 || lastScheduledRevision < 0) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
    }

    /** 批次续扫的确切用户位置来自当前轮转 Lane。 */
    public long lastUserId() {
        return laneProgress.isEmpty() ? 0 : laneProgress.get(accountLaneId).lastUserId();
    }

    public boolean complete() {
        return riskComplete && triggerComplete;
    }

    public State withTriggerProgress(boolean complete, int phase, long priceCursor, long orderCursor,
            long upperId, long markPriceTicks, long generatedAtEpochMillis) {
        return new State(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, complete, phase, priceCursor, orderCursor, upperId, markPriceTicks,
                generatedAtEpochMillis, triggerOcoOrderId, triggerOcoCursor, lastScheduledRevision, laneProgress);
    }

    public State withTriggerOcoProgress(long orderId, long cursor) {
        return new State(symbolId, accountLaneId, priceSequence, scanStartPriceSequence,
                riskComplete, triggerComplete, triggerPhase, triggerPriceCursor, triggerOrderCursor,
                triggerUpperId, triggerMarkPriceTicks, triggerGeneratedAtEpochMillis, orderId, cursor,
                lastScheduledRevision, laneProgress);
    }
}

    public record LiquidationOrder(long liquidationId, long userId, int symbolId, CoreMarginMode marginMode,
                                 CorePositionSide positionSide, CoreInstrument instrument,
                                 long triggerPriceSequence, long signedQuantitySteps, long closeQuantitySteps,
                                 long deficitUnits, long executionPriceTicks, long liquidationFeeRatePpm,
                                 long liquidationFeeUnits, CoreLiquidationState.Status status,
                                 long nextCancelOrderId) {
    public LiquidationOrder {
        if (liquidationId <= 0 || userId <= 0 || symbolId < 0 || marginMode == null || positionSide == null
                || instrument == null || triggerPriceSequence <= 0 || signedQuantitySteps == 0
                || closeQuantitySteps <= 0 || closeQuantitySteps > Math.absExact(signedQuantitySteps)
                || deficitUnits < 0 || executionPriceTicks < 0 || liquidationFeeRatePpm < 0
                || liquidationFeeRatePpm > 1_000_000 || liquidationFeeUnits < 0 || status == null
                || nextCancelOrderId < 0) {
            throw new IllegalArgumentException("invalid runtime liquidation");
        }
    }
}

    @RequiredArgsConstructor
public static final class LiquidationBatch {
    @Accessors(fluent = true, chain = false)
    @Getter
    private final int capacity;
    private Position[] positions;
    private long[] priceSequences;
    /** Lane 写入数量，完成发布后由 owner 读取。 */
    private int count;
    /** owner 唯一写入的起始编号，派发后由 Lane 读取。 */
    private long firstId;

    /** 返回本批新增的清算需求数，Owner 在 Lane 结果发布后据此预留连续编号。 */
    int size() { return count; }

    /** 开始下一批时清零有效数量和编号起点，复用原数组。 */
    public void reset() {
        count = 0;
        firstId = 0;
    }

    /** Lane 保存需要新建强平计划的持仓引用及触发价格序号。 */
    void add(Position position, long priceSequence) {
        if (positions == null) {
            positions = new Position[capacity];
            priceSequences = new long[capacity];
        }
        if (count == capacity) throw new IllegalStateException("risk creation budget exceeded");
        positions[count] = position;
        priceSequences[count++] = priceSequence;
    }

    /** Owner 为本批预留从 nextId 开始的连续编号，返回下一批可用的起点。整数溢出直接报错。 */
    long assign(long nextId) {
        long end = Math.addExact(nextId, count);
        firstId = nextId;
        return end;
    }

    /** 原账户 Lane 使用 Owner 已分配的编号，把本批需求逐条落为 PLANNED 强平记录。 */
    void apply(TradingAccountRuntime runtime) {
        if (firstId <= 0) throw new IllegalStateException("risk liquidation IDs not assigned");
        for (int i = 0; i < count; i++) {
            Position p = positions[i];
            runtime.putLiquidation(new RiskScanner.LiquidationOrder(firstId + i, p.userId(), p.symbolId(), p.marginMode(),
                    p.positionSide(), p.instrument(), priceSequences[i], p.signedQuantitySteps(),
                    Math.absExact(p.signedQuantitySteps()), 0, 0, 0, 0, CoreLiquidationState.Status.PLANNED, 0));
        }
    }
}

}
