package com.surprising.aeron.service.risk;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.model.CoreRiskStatus;
import com.surprising.aeron.protocol.CoreMarginMode;
import com.surprising.aeron.service.account.BalanceRuntime;
import com.surprising.aeron.service.account.UserRuntime;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPriceRuntime;
import com.surprising.aeron.service.order.state.OrderRuntime;
import com.surprising.aeron.service.order.state.ReservationRuntime;
import com.surprising.aeron.service.position.PositionRuntime;
import com.surprising.aeron.support.math.*;
import com.surprising.aeron.support.math.CoreContractMath;
import com.surprising.aeron.support.math.OptionContractMath;
import com.surprising.instrument.api.math.PerpetualContractMath;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import org.eclipse.collections.impl.list.mutable.primitive.LongArrayList;

/** 账户 Lane 内的风险估值主流程：按有界用户页继续持仓和委托扫描，写本 Lane 的风险结果、刷新或取消已有强平计划，并收集待编号的新强平需求；全局编号和总扫描游标由 Owner 负责。 构造约定：只提供 Lane 内的静态估值步骤，不创建额外的状态持有者。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RiskLaneProcessor {
    /**
     * 在本批预算内逐个处理用户；用户或行情修订变化时重置该用户累计值，用户完成后推进最后完成编号，返回本 Lane 的进度和新增强平需求。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param initial 该账户 Lane 开始本批扫描时的风险游标和累计金额
     * @param indexedUserIds 已经按编号升序排列、且属于该 Lane 的有界用户页
     * @param changedInstrument 本轮价格变化的合约配置，逐仓只重算该合约，全仓按结算资产合并估值
     * @param changedMark 本轮变化合约对应的固定行情输入，逐仓估值使用它
     * @param settleAssetId 本轮需要合并全仓风险的结算资产整数编号
     * @param changedSymbolId 本轮价格变化的币对整数编号，用来限定逐仓重算范围
     * @param maxWork 本批最多处理的工作项数量，用户遍历至少消耗一个单位以保证能退出
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     * @param expectedUserRevision 上次保留累计值时的用户修订号，变化后必须重新估值该用户
     * @param expectedMarketRevision 上次保留累计值时的市场修订号，变化后不能沿用旧的跨仓累加值
     */
    static Page scan(TradingAccountRuntime runtime, RiskScanRuntime initial,
                                        LongArrayList indexedUserIds,
                                        CoreInstrument changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RuntimeIdentityRegistry identities, long expectedUserRevision, long expectedMarketRevision) {
        // 当前 Lane 可复用的本批强平需求容器，容量受本次工作预算限制。
        RiskLiquidationBatch creations = runtime.riskLiquidationBatch(maxWork);
        // 本批正在推进的扫描游标，每处理完一个用户片段就接收新进度。
        RiskScanRuntime progress = initial;
        // 本批还剩多少工作预算，任何用户处理至少扣一个单位。
        int remaining = maxWork;
        // 当前用户这份累计结果所依据的最新修订号，随页回执交给 Owner 保存。
        long userRevision = expectedUserRevision;
        while (remaining > 0) {
            // 沿旧用户游标继续或从有序页选出的用户，记录不存在时本 Lane 这一页结束。
            UserRuntime user = progress.riskUserId() == 0
                    ? nextUser(runtime, indexedUserIds, progress.lastUserId())
                    : runtime.user(progress.riskUserId());
            if (user == null) {
                return new Page(progress, maxWork - remaining, true, creations, userRevision);
            }
            if (progress.riskUserId() == 0 || user.revision() != userRevision
                    || expectedMarketRevision != runtime.marketRevision()) {
                progress = progress.withRiskProgress(false, user.userId(), 0, "-", 0,
                        0, 0, 0, 0, progress.lastUserId());
            }
            userRevision = user.revision();
            expectedMarketRevision = runtime.marketRevision();
            // 本次处理一个用户得到的游标、消耗工作量及是否完成的回执。
            UserPage page = processUser(runtime, progress, user, changedInstrument, changedMark,
                    settleAssetId, changedSymbolId, remaining, creations, identities);
            progress = page.scan();
            // 本次从预算中扣除的工作量，最少为 1，避免空记录使循环无法推进。
            int consumed = Math.max(1, page.workUnits());
            remaining -= consumed;
            if (page.complete()) {
                progress = progress.withRiskProgress(false, 0, 0, "-", 0,
                        0, 0, 0, 0, user.userId());
            }
        }
        // 没有未完成用户且有序页中也无下一用户时，才表明本 Lane 当前遍历已结束。
        boolean complete = progress.riskUserId() == 0
                && nextUser(runtime, indexedUserIds, progress.lastUserId()) == null;
        return new Page(progress, maxWork - remaining, complete, creations, userRevision);
    }

    /**
     * 按三个阶段继续当前用户：0 累计同资产持仓风险并重算变化合约的逐仓风险，1 累计逐仓委托冻结，2 用共享权益写入各全仓持仓风险；预算耗尽时保存完整游标。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param scan 本次分批处理的风险游标及账户累计值
     * @param user 正在估值的当前 Lane 用户记录
     * @param changedInstrument 本轮价格变化的合约配置，逐仓只重算该合约，全仓按结算资产合并估值
     * @param changedMark 本轮变化合约对应的固定行情输入，逐仓估值使用它
     * @param settleAssetId 本轮需要合并全仓风险的结算资产整数编号
     * @param changedSymbolId 本轮价格变化的币对整数编号，用来限定逐仓重算范围
     * @param maxWork 本批最多处理的工作项数量，用户遍历至少消耗一个单位以保证能退出
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static UserPage processUser(TradingAccountRuntime runtime,
                                        RiskScanRuntime scan, UserRuntime user,
                                        CoreInstrument changedInstrument, MarkPriceRuntime changedMark,
                                        int settleAssetId, int changedSymbolId, int maxWork,
                                        RiskLiquidationBatch creations,
                                        RuntimeIdentityRegistry identities) {
        // 用户估值所处阶段：0 扫持仓，1 扫逐仓委托冻结，2 写全仓风险。
        int phase = scan.riskPhase();
        // 当前阶段最后处理的持仓业务键，换阶段时重置为短横线。
        String positionCursor = scan.riskPositionCursor();
        // 当前用户最后处理的冻结委托编号，便于预算用完后继续。
        long reservationCursor = scan.riskReservationCursor();
        // 同一结算资产下已累计的全仓权益变化，期货用浮盈亏，期权用市值。
        long unrealized = scan.riskUnrealizedPnlUnits();
        // 同一结算资产下已累计的全仓维持保证金，后续用它判断共享风险。
        long maintenance = scan.riskMaintenanceMarginUnits();
        // 该资产已累计的逐仓持仓保证金，不能再次计入全仓共享钱包。
        long isolatedMargin = scan.riskIsolatedMarginUnits();
        // 该资产已累计的逐仓委托冻结，计算全仓钱包时还要扣掉这部分。
        long isolatedReservation = scan.riskIsolatedReservationUnits();
        // 本页按总余额扣逐仓占用后得到的全仓钱包；最小 long 表示尚未计算。
        long wallet = Long.MIN_VALUE;
        // 当前账户 Lane 复用的估值暂存区，读取单个持仓后覆盖计算结果。
        PositionRiskScratch positionRisk = runtime.accountLane(scan.accountLaneId()).positionRiskScratch();
        // 同一条常驻 Lane 线程可能在旧扫描失效后继续处理新价格。
        // 把小型行情缓存绑定到当前市场修订，避免继续使用旧标记价。
        positionRisk.scanMarketRevision = runtime.marketRevision();
        // 本用户片段已经处理的持仓或冻结记录数量，达到上限就保存继续游标。
        int work = 0;
        while (work < maxWork) {
            if (phase == 0) {
                // 游标后下一份持仓的运行时整数键，0 表示当前持仓遍历到末尾。
                long positionIdentity = nextPositionKey(runtime, identities, user.userId(), positionCursor);
                if (positionIdentity == 0) {
                    phase = 1;
                    positionCursor = "-";
                    continue;
                }
                positionCursor = identities.positionKey(user.userId(), positionIdentity);
                // 本轮要累加或写风险的 Lane 持仓，空仓和不属于本结算资产的记录按阶段跳过。
                PositionRuntime position = runtime.position(positionIdentity);
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
                // 游标后的下一份冻结委托，遍历结束后才能进入全仓风险写入阶段。
                ReservationRuntime reservation = nextReservation(runtime, user.userId(), reservationCursor);
                if (reservation == null) {
                    if (maintenance == 0) {
                        return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                                unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                                work, true);
                    }
                    phase = 2;
                    positionCursor = "-";
                    continue;
                }
                reservationCursor = reservation.orderId();
                work++;
                // 冻结委托对应的订单，读取它的保证金模式判断是否属于逐仓占用。
                OrderRuntime order = runtime.order(reservation.orderId());
                if (order != null && order.marginMode() == CoreMarginMode.ISOLATED
                        && reservation.assetId() == settleAssetId) {
                    isolatedReservation = Math.addExact(isolatedReservation, reservation.reservedUnits());
                }
                continue;
            }
            // 游标后下一份持仓的运行时整数键，0 表示当前持仓遍历到末尾。
            long positionIdentity = nextPositionKey(runtime, identities, user.userId(), positionCursor);
            if (positionIdentity == 0) {
                return new UserPage(scan.withRiskProgress(false, user.userId(), 0, "-", 0,
                        unrealized, maintenance, isolatedMargin, isolatedReservation, scan.lastUserId()),
                        work, true);
            }
            positionCursor = identities.positionKey(user.userId(), positionIdentity);
            // 本轮要累加或写风险的 Lane 持仓，空仓和不属于本结算资产的记录按阶段跳过。
            PositionRuntime position = runtime.position(positionIdentity);
            work++;
            if (position.signedQuantitySteps() == 0 || position.marginMode() != CoreMarginMode.CROSS
                    || position.assetId() != settleAssetId) continue;
            if (!risk(runtime, position, identities, positionRisk)) continue;
            // 当前 Lane 处理这一页时，账户余额和逐仓占用保持在同一所有权范围。
            // 每页只计算一次全仓钱包，供该用户后续全仓持仓复用。
            if (wallet == Long.MIN_VALUE) {
                // 当前用户在本结算资产的账户余额，用可用加冻结得出钱包总额。
                BalanceRuntime balance = runtime.balance(user.userId(), settleAssetId);
                // 该资产的可用与冻结余额之和，尚未扣除逐仓持仓和逐仓委托占用。
                long total = balance == null ? 0 : Math.addExact(balance.availableUnits(), balance.lockedUnits());
                wallet = Math.subtractExact(Math.subtractExact(total, isolatedMargin), isolatedReservation);
                if (wallet < 0) throw new IllegalStateException("isolated margin exceeds wallet balance");
            }
            // 全仓钱包加同资产全仓持仓估值变化后的共享权益。
            long equity = Math.addExact(wallet, unrealized);
            // 本次维持保证金相对权益的 ppm 风险占比，用于正常、预警或强平判级。
            long ratio = riskRatio(maintenance, equity);
            putRiskAndLiquidation(runtime, user.userId(), positionCursor, position,
                    positionRisk.instrument, positionRisk.priceSequence, equity, positionRisk.unrealized,
                    positionRisk.maintenance, ratio, creations, identities);
        }
        return new UserPage(scan.withRiskProgress(false, user.userId(), phase, positionCursor,
                reservationCursor, unrealized, maintenance, isolatedMargin, isolatedReservation,
                scan.lastUserId()), work, false);
    }

    /**
     * 仅用这份逐仓持仓的保证金和估值变化计算权益，再写风险记录及强平需求；期货使用浮盈亏，期权使用带方向市值。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param positionKey 用户持仓的业务键，净持仓用合约编号，双向持仓还区分持仓腿
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param instrument 当前持仓所属合约的类型、精度和保证金配置
     * @param mark 当前持仓估值采用的行情，包括标记价、指数价、远期价和价格序号
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static void updateIsolated(TradingAccountRuntime runtime, long userId, String positionKey,
                                       PositionRuntime position, CoreInstrument instrument,
                                       MarkPriceRuntime mark, RiskLiquidationBatch creations,
                                       RuntimeIdentityRegistry identities) {
        // 当前逐仓持仓在新标记价下的浮盈亏，写风险记录时单独保留。
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
        // 按当前数量、风险档位和行情算出的逐仓维持保证金。
        long maintenance = CoreContractMath.maintenanceMarginUnits(instrument,
                position.signedQuantitySteps(), mark.markPriceTicks(), mark.indexPriceTicks(),
                mark.forwardPriceTicks());
        // 逐仓权益的估值变化，期货使用浮盈亏，期权使用带方向市值。
        long equityDelta = instrument.contractType().isOption()
                ? OptionContractMath.optionMarketValueUnits(instrument, position.signedQuantitySteps(),
                mark.markPriceTicks()) : unrealized;
        // 这份逐仓持仓自己的保证金加估值变化，不混入其他账户资金。
        long equity = Math.addExact(position.positionMarginUnits(), equityDelta);
        // 本次维持保证金相对权益的 ppm 风险占比，用于正常、预警或强平判级。
        long ratio = riskRatio(maintenance, equity);
        putRiskAndLiquidation(runtime, userId, positionKey, position, instrument, mark.priceSequence(), equity,
                unrealized, maintenance, ratio, creations, identities);
    }

    /**
     * 风险事实有变化才写新估值；结算或关闭中的合约不新建强平。其余持仓不再危险时取消尚未执行的计划，仍危险时刷新计划，尚无计划时加入待编号需求。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param positionKey 用户持仓的业务键，净持仓用合约编号，双向持仓还区分持仓腿
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param instrument 当前持仓所属合约的类型、精度和保证金配置
     * @param priceSequence 本次风险结果对应的行情序号，也作为强平计划的触发价格版本
     * @param equity 本次风险比较采用的权益金额，单位为结算资产最小单位
     * @param unrealized 该持仓在当前标记价下的未实现盈亏，单位为结算资产最小单位
     * @param maintenance 维持持仓所需的保证金，单位为结算资产最小单位
     * @param ratio 维持保证金相对权益的风险占比，按一百万倍保存
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     */
    private static void putRiskAndLiquidation(TradingAccountRuntime runtime, long userId, String positionKey,
                                              PositionRuntime position, CoreInstrument instrument,
                                              long priceSequence, long equity, long unrealized, long maintenance,
                                              long ratio, RiskLiquidationBatch creations,
                                              RuntimeIdentityRegistry identities) {
        // 根据风险占比选出的正常、预警或强平结论。
        CoreRiskStatus status = CoreRiskPolicy.status(ratio);
        // 当前持仓的币对整数编号，定位已有强平及行情缓存时使用。
        int symbolId = position.symbolId();
        // 该用户、币对和持仓腿对应的风险记录整数键。
        long riskKey = identities.preparedPositionKey(userId, positionKey);
        // 这份持仓上一次发布的风险记录，所有风险事实相同就继续复用它。
        RiskSnapshotRuntime previousSnapshot = runtime.riskSnapshot(riskKey);
        // 分批继续或新一轮扫描可能再次遇到没有变化的持仓。
        // 风险事实相同就保留原风险记录，省去新记录和变更缓冲区写入。
        if (previousSnapshot == null || previousSnapshot.priceSequence() != priceSequence
                || previousSnapshot.equityUnits() != equity
                || previousSnapshot.unrealizedPnlUnits() != unrealized
                || previousSnapshot.maintenanceMarginUnits() != maintenance
                || previousSnapshot.marginRatioPpm() != ratio
                || previousSnapshot.positionSide() != position.positionSide()) {
            runtime.putRiskSnapshot(riskKey, new RiskSnapshotRuntime(userId, symbolId, position.positionSide(),
                    priceSequence, equity, unrealized, maintenance, ratio));
        }
        // 到期结算流程按已批准价格处理剩余持仓。这里仍保留风险估值，
        // 但结算分批期间不再为同一持仓创建竞争的强平流程。
        if (instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.SETTLEMENT
                || instrument.maintenance().mode() == com.surprising.aeron.protocol.CoreInstrumentMaintenance.Mode.CLOSED) {
            return;
        }
        // 该用户当前持仓腿已有的活动强平记录，已进入执行的流程不在这里重建。
        LiquidationRuntime active = runtime.activeLiquidation(userId, symbolId, position.positionSide());
        if (status != CoreRiskStatus.LIQUIDATION
                || !CoreRiskPolicy.canLiquidate(instrument.contractType(), position.signedQuantitySteps())) {
            if (active != null && active.status() == CoreLiquidationState.Status.PLANNED) {
                runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), active.userId(),
                        active.symbolId(), active.marginMode(), active.positionSide(), active.instrument(),
                        active.triggerPriceSequence(), active.signedQuantitySteps(), active.closeQuantitySteps(),
                        0, 0, 0, 0, CoreLiquidationState.Status.CANCELED, 0));
            }
            return;
        }
        if (active != null) {
            if (active.status() == CoreLiquidationState.Status.PLANNED) {
                // 刷新尚未执行计划时读取的最新带方向持仓步数。
                long quantity = position.signedQuantitySteps();
                if (active.userId() != userId || active.symbolId() != symbolId
                        || active.marginMode() != position.marginMode()
                        || active.positionSide() != position.positionSide()
                        || active.instrument() != instrument
                        || active.triggerPriceSequence() != priceSequence
                        || active.signedQuantitySteps() != quantity
                        || active.closeQuantitySteps() != Math.absExact(quantity)) {
                    runtime.replaceLiquidation(new LiquidationRuntime(active.liquidationId(), userId, symbolId,
                            position.marginMode(), position.positionSide(), instrument, priceSequence,
                            quantity, Math.absExact(quantity), 0, 0, 0, 0,
                            CoreLiquidationState.Status.PLANNED, 0));
                }
            }
            return;
        }
        creations.add(position, priceSequence);
    }

    /**
     * 取得当前持仓的配置和行情，必要时刷新 Lane 内市场缓存，再算浮盈亏、权益变化和维持保证金；缺少配置或行情时返回 false，调用方跳过该持仓。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     * @param result 本 Lane 复用的持仓估值暂存区，计算成功后覆盖其中的市场与金额字段
     */
    private static boolean risk(TradingAccountRuntime runtime, PositionRuntime position,
                                RuntimeIdentityRegistry identities, PositionRiskScratch result) {
        // 当前持仓的币对整数编号，定位已有强平及行情缓存时使用。
        int symbolId = position.symbolId();
        // 暂存区内的配置引用；币对、市场修订或运行时变化时重新取得配置。
        CoreInstrument instrument = result.instrument;
        // 暂存区内的行情引用；缓存失效后与配置一起从当前运行时重新读取。
        MarkPriceRuntime mark = result.mark;
        if (instrument == null || mark == null || result.symbolId != symbolId
                || instrument != position.instrument()
                || result.marketRevision != result.scanMarketRevision
                || result.runtime != runtime) {
            instrument = runtime.instrument(identities.preparedSymbol(symbolId));
            mark = runtime.markPrice(symbolId);
            result.symbolId = symbolId;
            result.instrument = instrument;
            result.mark = mark;
            result.marketRevision = result.scanMarketRevision;
            result.runtime = runtime;
        }
        if (instrument == null || mark == null) return false;
        // 本持仓在有效行情标记价下的未实现盈亏，随后写入暂存区供调用方使用。
        long unrealized = unrealized(position, instrument, mark.markPriceTicks());
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

    /**
     * 按合约类型、带方向数量、入场价及当前标记价计算未实现盈亏，结果使用结算资产最小整数单位。
     * @param position 当前 Lane 持有的目标持仓对象，数量及金额按该对象最新值计算
     * @param instrument 当前持仓所属合约的类型、精度和保证金配置
     * @param markPriceTicks 本次用于计算浮动盈亏的标记价格 tick
     */
    private static long unrealized(PositionRuntime position, CoreInstrument instrument, long markPriceTicks) {
        return PerpetualContractMath.unrealizedPnlUnits(instrument.contractType(), position.signedQuantitySteps(),
                position.entryPriceTicks(), markPriceTicks, instrument.notionalMultiplierUnits(),
                instrument.priceTickUnits(), instrument.settleScaleUnits());
    }

    /**
     * 把维持保证金除以权益后放大一百万倍；不需维持保证金时为 0，权益不为正或中间乘法溢出时用最大 long 表示极高风险。
     * @param maintenance 维持持仓所需的保证金，单位为结算资产最小单位
     * @param equity 本次风险比较采用的权益金额，单位为结算资产最小单位
     */
    private static long riskRatio(long maintenance, long equity) {
        if (maintenance <= 0) return 0;
        if (equity <= 0) return Long.MAX_VALUE;
        try {
            return Math.multiplyExact(maintenance, 1_000_000L) / equity;
        } catch (/* 风险占比放大一百万倍时发生整数溢出，按极高风险返回最大 long。 */ ArithmeticException exception) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * 在有序用户页中二分定位游标，只返回其后的下一用户；页尾没有后续用户时返回 null。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param users 按用户编号升序排列的当前 Lane 候选用户页
     * @param cursor 最后处理的用户或记录位置，查找时只返回它后面的下一项
     */
    private static UserRuntime nextUser(TradingAccountRuntime runtime, LongArrayList users, long cursor) {
        // 用户游标在有序页中的位置，命中时向后一项，未命中时取插入点。
        int slot = users.binarySearch(cursor);
        slot = slot >= 0 ? slot + 1 : -slot - 1;
        return slot == users.size() ? null : runtime.user(users.get(slot));
    }

    /**
     * 沿当前用户的风险持仓索引寻找业务键之后的下一持仓整数键，遍历顺序由运行时统一维护。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param identities 业务编号与运行时整数键的映射，用于定位币对、资产和用户持仓
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param cursor 最后处理的持仓业务键，短横线表示尚未开始
     */
    private static long nextPositionKey(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                        long userId, String cursor) {
        return runtime.nextRiskPositionKey(userId, cursor, identities);
    }

    /**
     * 沿用户冻结委托索引寻找游标后的下一笔，0 编号代表没有更多记录并转换为 null。
     * @param runtime 本轮操作的交易运行时，账户数据只能在所属 Lane 的权限范围内读取或修改
     * @param userId 当前被估值或需要定位持仓、冻结记录的用户编号
     * @param cursor 最后处理的冻结委托编号，0 表示尚未开始
     */
    private static ReservationRuntime nextReservation(TradingAccountRuntime runtime, long userId, long cursor) {
        // 下一份冻结委托的编号，0 表示已没有后续冻结记录。
        long id = runtime.nextRiskReservationId(userId, cursor);
        return id == 0 ? null : runtime.reservation(id);
    }

    /**
     * 一个账户 Lane 复用的单持仓估值暂存区，处理下一持仓时覆盖；只缓存当前币对的配置和行情，跨市场修订或跨运行时不能沿用旧缓存。
     */
    public static final class PositionRiskScratch {
        /** 缓存币对的配置引用，用来换算价格、数量和保证金。 */
        private CoreInstrument instrument;
        /** 缓存币对本次采用的行情引用，不能跨失效的市场修订继续用。 */
        private MarkPriceRuntime mark;
        /** 缓存币对的整数编号，-1 表示尚未缓存任何币对。 */
        private int symbolId = -1;
        /** 缓存行情实际对应的市场修订号，初始最小 long 表示尚未建立缓存。 */
        private long marketRevision = Long.MIN_VALUE;
        /** 本次扫描要求的市场修订号，进入用户处理时刷新，用来识别旧行情缓存。 */
        private long scanMarketRevision = Long.MIN_VALUE;
        /** 缓存所对应的运行时引用，防止同一暂存区误用另一运行时的市场数据。 */
        private TradingAccountRuntime runtime;
        /** 当前成功估值使用的行情序号，写风险记录和强平计划时带上它。 */
        private long priceSequence;
        /** 当前持仓的未实现盈亏，单位为结算资产最小整数单位。 */
        private long unrealized;
        /** 当前持仓所需维持保证金，单位为结算资产最小整数单位。 */
        private long maintenance;
        /** 当前持仓计入权益的变化额，期货是未实现盈亏，期权是带方向市值。 */
        private long equityDelta;
    }

    /**
     * 一个账户 Lane 本批扫描的回执，Owner 收集后提交各 Lane 游标，并按固定顺序给新增强平需求分配编号。
     * @param scan 本次分批处理的风险游标及账户累计值
     * @param workUnits 本批实际处理的工作项数量，Owner 用它扣减共享预算
     * @param complete 对应账户 Lane 或当前用户是否已完整遍历，不能仅凭本批用完预算判断完成
     * @param creations 本批新增强平需求的有界容器，Owner 稍后按固定顺序分配编号
     * @param userRevision 当前未完成用户累计值对应的状态修订号，供下次继续时判断是否失效
     */
    record Page(RiskScanRuntime scan, int workUnits, boolean complete, RiskLiquidationBatch creations, long userRevision) { }
    /**
     * 单个用户在本批内的估值进度及工作量，用来区分用户已经算完与预算耗尽后还要继续两种情况。
     * @param scan 本次分批处理的风险游标及账户累计值
     * @param workUnits 本批实际处理的工作项数量，Owner 用它扣减共享预算
     * @param complete 对应账户 Lane 或当前用户是否已完整遍历，不能仅凭本批用完预算判断完成
     */
    private record UserPage(RiskScanRuntime scan, int workUnits, boolean complete) { }
}
