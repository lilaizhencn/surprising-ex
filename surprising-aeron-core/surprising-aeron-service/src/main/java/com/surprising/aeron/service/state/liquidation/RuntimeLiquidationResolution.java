package com.surprising.aeron.service.state.liquidation;

import com.surprising.aeron.service.state.risk.LiquidationRuntime;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.TradingRuntimeState;
import com.surprising.aeron.service.state.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.aeron.service.state.ledger.InsuranceAllocationPolicy;
import com.surprising.aeron.service.state.ledger.RuntimeTreasuryDelta;
import com.surprising.aeron.service.state.ledger.SystemLedgerCommand;
import com.surprising.aeron.service.state.ledger.TreasuryRuntime;
import com.surprising.aeron.service.state.model.CoreLiquidationState;

/**
 * 强平步骤 7：由后续 RESOLVE_LIQUIDATION 命令确认结果，按决议补亏或完成。
 *
 * 强平执行后的保险基金补亏与完成判定。先由账户 Lane 捕获同资产保险索赔事实，账本按确定顺序校验分配并扣保险余额和亏空，再交源账户 Lane 发布强平新状态；真正 ADL 走单独的减仓流程。
 */
public final class RuntimeLiquidationResolution {

    /**
     * 只提供强平补亏入口，命令执行进度保存在可复用续步中。
     */
    private RuntimeLiquidationResolution() {
    }

    /**
     * 核对命令前业务状态和运行时的产品线及修订号，使用业务状态中的强平编号集合进入同步补亏入口。
     * @param before 本命令前的业务状态，用来校验产品线与运行时修订号一致
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     */
    public static TradingRuntimeState apply(TradingCoreState before,
                                            ResolveLiquidationCommand command,
                                            TradingRuntimeState runtime,
                                            RuntimeIdentityRegistry identities) {
        if (before == null || runtime == null || before.productLine() != runtime.productLine()
                || before.revision() != runtime.revision()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        return applyRuntime(command, runtime, identities, before.riskState().liquidations().keySet());
    }

    /**
     * 在账本线程未启动时同步处理保险补亏或完成；账本已经启动则要求改用异步续步。
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
     */
    public static TradingRuntimeState applyRuntime(ResolveLiquidationCommand command,
                                                   TradingRuntimeState runtime,
                                                   RuntimeIdentityRegistry identities,
                                                   Iterable<Long> candidateIds) {
        if (runtime.systemLedgerStarted()) throw new IllegalStateException("active resolution requires its asynchronous continuation");
        resolve(null, command, runtime, identities, candidateIds, false);
        return runtime;
    }

    /**
     * 没有旧续步时开始异步补亏，内部创建续步并派发各相关账户的索赔读取。
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
     */
    public static java.util.function.BooleanSupplier begin(ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return begin(null, command, runtime, identities, candidateIds);
    }

    /**
     * 可回收的旧续步连同资金差额存储一起复用，否则新建，再准备本次索赔和补亏工作。
     * @param reuse 上次已经可回收的补亏续步，未提供或尚不可复用时新建
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
     */
    public static ResolutionWork begin(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        return resolve(reuse, command, runtime, identities, candidateIds, true);
    }

    /**
     * Owner 校验衍生品输入并定位源强平、合约和结算资产；异步时先派发索赔读取，同步时直接准备分配、执行账本判断，再由源 Lane 更新强平并完成。
     * @param reuse 上次已经可回收的补亏续步，未提供或尚不可复用时新建
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
     * @param asynchronous 是否只派发跨线程工作并返回可轮询续步；同步路径在本次调用内完成
     */
    private static ResolutionWork resolve(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingRuntimeState runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds,
            boolean asynchronous) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
        if (asynchronous) {
            // Owner 可读的候选或源强平路由，提供用户及合约信息以安排所属 Lane 读取。
            var route = runtime.liquidationRoute(command.liquidationId());
            if (route == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation route is missing");
            // 源强平或当前候选的合约配置，用来确定并过滤结算资产。
            CoreInstrument instrument = requireInstrument(runtime, route.instrumentId());
            // 源强平的结算资产编号，保险候选和账本都限定在这项资产。
            int assetId = identities.assetId(instrument.settleAsset());
            // 本命令新建或复用的补亏续步，异步路径准备好后返回 Owner。
            ResolutionWork work = reuse == null || !reuse.reusable() ? new ResolutionWork() : reuse;
            work.sourceUserId = route.userId();
            work.prepare(runtime, command, null, assetId, null);
            work.prepareClaimReads(candidateIds, identities);
            return work;
        }
        // 同步路径读取的源强平记录，缺失时拒绝继续处理。
        LiquidationRuntime liquidation = runtime.liquidation(command.liquidationId());
        if (liquidation == null) {
            throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
        }
        // 源强平或当前候选的合约配置，用来确定并过滤结算资产。
        CoreInstrument instrument = requireInstrument(runtime, identities.instrumentId(liquidation.symbolId()));
        // 源强平的结算资产编号，保险候选和账本都限定在这项资产。
        int assetId = identities.assetId(instrument.settleAsset());
        // 同步保险决议预先准备的候选索赔输入，非保险决议不需要它。
        InsuranceAllocationPolicy.ResolutionInputs allocation = command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE
                ? InsuranceAllocationPolicy.prepareResolution(runtime, identities, candidateIds, liquidation, assetId) : null;
        // 本命令新建或复用的补亏续步，异步路径准备好后返回 Owner。
        ResolutionWork work = reuse == null || !reuse.reusable() ? new ResolutionWork() : reuse;
        work.prepare(runtime, command, liquidation, assetId, allocation);
        if (asynchronous) return work;
        work.applyLedger(runtime.treasury());
        runtime.releaseOwnerLaneAccess();
        runtime.executeLaneMutations(work.laneMask, 1, work);
        work.finish();
        return null;
    }

    /**
     * 命令槽复用的强平补亏续步，按索赔读取、账本预算及记账、源账户更新的顺序推进；账本与账户都完成后才增加全局修订号。
     */
    public static final class ResolutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 账本处理本决议时复用的保险余额和亏空变化缓冲，每次准备命令先清空。 */
        private final RuntimeTreasuryDelta treasuryDelta = new RuntimeTreasuryDelta();
        /** 本续步操作的运行时，Owner 通过它派发账户和账本工作。 */
        private TradingRuntimeState runtime;
        /** 当前保险补亏或完成命令，成功结束时释放引用。 */
        private ResolveLiquidationCommand command;
        /** liquidation：源账户 Lane 捕获的待处理强平记录，账本根据它判断剩余亏空与阶段；nextLiquidation：账本根据实际覆盖额生成的新强平记录，随后交回源账户 Lane 写入。 */
        private LiquidationRuntime liquidation, nextLiquidation;
        /** 同资产保险索赔的确定性分配输入，账本用它校验本笔是否轮到处理及覆盖额。 */
        private InsuranceAllocationPolicy.ResolutionInputs allocation;
        /** 源合约结算资产整数编号，保险池、索赔和亏空必须使用同一资产。 */
        private int assetId;
        /** 最终需要写入强平新状态的源账户 Lane 位集合。 */
        private long laneMask;
        /** done：账本和源账户写入都完成后的标记，重复轮询直接返回完成；ledgerDispatched：本决议账本命令是否已经成功入队，防止重复扣保险余额；accountsDispatched：账本完成后是否已经派发源账户新状态写入。 */
        private boolean done, ledgerDispatched, accountsDispatched;
        /** 源强平路由中的用户编号，用于还未读取记录时定位所属 Lane。 */
        private long sourceUserId;
        /** 当前是否正在等待账户 Lane 提供源强平和保险索赔回执。 */
        private boolean capturingClaims;
        /** 按本批下标保存的候选强平编号，数组可随复用批次扩容。 */
        private long[] claimIds = new long[4];
        /** 与 claimIds 同下标的账户 Lane 编号，保证每个 Lane 只读取自己的索赔。 */
        private int[] claimLanes = new int[4];
        /** 各账户 Lane 写入的有效待保险索赔记录，读取完成后供账本只读计算分配。 */
        private LiquidationRuntime[] claimReceipts = new LiquidationRuntime[4];
        /** 本次候选数组的有效长度，只有此前缀参与读取、分配和清理。 */
        private int claimCount;

        /**
         * 绑定本次补亏命令与源强平，预检查修订号容量、清空资金差额并重置账本及账户阶段；异步源记录尚未读取时用路由用户定位 Lane。
         * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
         * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
         * @param liquidation 本命令要继续处理的强平记录，异步准备时可为空，随后由源账户 Lane 读取
         * @param assetId 源强平结算资产的整数编号，保险和亏空总账都按该资产记账
         * @param allocation 确定性保险分配的输入快照，异步路径先留空，等待账户回执后由账本准备
         */
        private void prepare(TradingRuntimeState runtime, ResolveLiquidationCommand command,
                LiquidationRuntime liquidation, int assetId, InsuranceAllocationPolicy.ResolutionInputs allocation) {
            initializeLedgerCommand(runtime);
            runtime.requireRevisionCapacity();
            this.runtime = runtime; this.command = command; this.liquidation = liquidation;
            this.assetId = assetId; this.allocation = allocation;
            laneMask = runtime.topology().accountLaneMask(liquidation == null ? sourceUserId : liquidation.userId());
            nextLiquidation = null;
            treasuryDelta.clear();
            done = ledgerDispatched = accountsDispatched = capturingClaims = false;
        }

        /**
         * 保险补亏时收集同结算资产候选强平的编号与 Lane，按需扩容复用数组，再一次派发源强平和候选索赔读取；其他决议只读取源强平。
         * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
         * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
         */
        private void prepareClaimReads(Iterable<Long> candidateIds, RuntimeIdentityRegistry identities) {
            claimCount = 0;
            // 索赔读取阶段需要访问的所有账户 Lane 位集合，始终包含源强平的 Lane。
            long readMask = laneMask;
            if (command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE) {
                for (long /* 正在检查的候选强平编号，仅同资产且路由存在时加入本批读取。 */ id : candidateIds) {
                    // Owner 可读的候选或源强平路由，提供用户及合约信息以安排所属 Lane 读取。
                    var route = runtime.liquidationRoute(id);
                    if (route == null) continue;
                    // 源强平或当前候选的合约配置，用来确定并过滤结算资产。
                    var instrument = runtime.instrument(route.instrumentId());
                    if (instrument == null || identities.assetId(instrument.settleAsset()) != assetId) continue;
                    if (claimCount == claimIds.length) {
                        // 候选数达到原容量时计算出的双倍数组长度，三个并行数组一起扩容。
                        int capacity = Math.multiplyExact(claimCount, 2);
                        claimIds = java.util.Arrays.copyOf(claimIds, capacity);
                        claimLanes = java.util.Arrays.copyOf(claimLanes, capacity);
                        claimReceipts = java.util.Arrays.copyOf(claimReceipts, capacity);
                    }
                    // 本候选强平用户所属的 Lane 编号，写入读取路由并并入位集合。
                    int laneId = runtime.topology().accountLaneId(route.userId());
                    claimIds[claimCount] = id; claimLanes[claimCount] = laneId; claimReceipts[claimCount++] = null;
                    readMask |= 1L << laneId;
                }
            }
            capturingClaims = true;
            runtime.dispatchControlLanes(readMask, this);
        }

        /**
         * 账本校验强平阶段、确定分配顺序及期望覆盖额，只按可用保险余额覆盖亏空并生成新强平记录；亏空未清转 ADL_REQUIRED，普通 ADL 决议在这里拒绝。
         * @param ledger 当前账本线程持有的资金总账，只有在账本执行阶段应用资金差额
         */
        @Override protected void applyLedger(TreasuryRuntime ledger) {
            if (command.resolution() == ResolveLiquidationCommand.Resolution.INSURANCE && allocation == null)
                allocation = InsuranceAllocationPolicy.prepareResolution(claimReceipts, claimCount, liquidation, assetId);
            // 账本按本次保险覆盖额扣减后的剩余亏空，随后写入新的强平记录。
            long nextDeficit = liquidation.deficitUnits();
            // 账本验证后决定的新阶段：亏空归零可完成，否则进入待 ADL。
            CoreLiquidationState.Status nextStatus;
            switch (command.resolution()) {
                case INSURANCE -> {
                    if (liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        throw new CoreStateRejectedException("LIQUIDATION_STATE_CONFLICT", "insurance resolution requires insurance state");
                    if (command.coveredUnits() > liquidation.deficitUnits())
                        throw new CoreStateRejectedException("INSURANCE_COVER_EXCEEDS_DEFICIT", "insurance coverage must be within liquidation deficit");
                    // 保险分配规则给出的本笔处理优先级和期望覆盖额，命令声明必须与它一致。
                    InsuranceAllocationPolicy.Resolution result = InsuranceAllocationPolicy.resolve(allocation, ledger);
                    if (!result.next()) throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                    if (command.coveredUnits() != result.expectedCoverage())
                        throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH", "insurance coverage does not match deterministic allocation");
                    if (command.coveredUnits() > ledger.insurance(assetId))
                        throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
                    if (command.coveredUnits() != 0) {
                        treasuryDelta.addInsurance(assetId, Math.negateExact(command.coveredUnits()));
                        treasuryDelta.addDeficit(assetId, Math.negateExact(command.coveredUnits()));
                    }
                    nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                    nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
                }
                case ADL -> throw new CoreStateRejectedException("INVALID_COMMAND", "ADL resolution requires atomic target deleveraging");
                case COMPLETED -> {
                    if (command.coveredUnits() != 0) throw new CoreStateRejectedException("INVALID_COMMAND", "completed resolution covers no units");
                    if (liquidation.deficitUnits() != 0) throw new CoreStateRejectedException("LIQUIDATION_DEFICIT_REMAINS",
                            "liquidation deficit must be fully covered before completion");
                    nextStatus = CoreLiquidationState.Status.COMPLETED;
                }
                default -> throw new IllegalStateException("unknown liquidation resolution");
            }
            treasuryDelta.apply(ledger);
            // 本次源强平记录，复制其他执行事实，只替换剩余亏空和处理状态。
            LiquidationRuntime current = liquidation;
            nextLiquidation = new LiquidationRuntime(current.liquidationId(), current.userId(), current.symbolId(),
                    current.marginMode(), current.positionSide(), current.instrument(), current.triggerPriceSequence(),
                    current.signedQuantitySteps(), current.closeQuantitySteps(), nextDeficit, current.executionPriceTicks(),
                    current.liquidationFeeRatePpm(), current.liquidationFeeUnits(), nextStatus, 0);
        }

        /**
         * 账户 Lane 在读取阶段提供自己持有的源强平及待保险索赔回执；账本完成后再次调用时，只由源 Lane 写入已经生成的新强平状态。
         * @param laneId 当前执行索赔读取或源强平更新的账户 Lane 编号
         */
        @Override public Object apply(int laneId) {
            if (capturingClaims) {
                if (runtime.currentLaneOwns(sourceUserId)) {
                    liquidation = runtime.liquidation(command.liquidationId());
                    if (liquidation == null) throw new CoreStateRejectedException("LIQUIDATION_NOT_FOUND", "liquidation plan does not exist");
                }
                for (/* 当前需要由本 Lane 读取的候选索赔数组下标。 */ int index = 0; index < claimCount; index++) {
                    if (claimLanes[index] != laneId) continue;
                    // 当前 Lane 读到的候选强平，仅处于 INSURANCE_REQUIRED 时才作为有效保险索赔回执。
                    var value = runtime.liquidation(claimIds[index]);
                    if (value != null && value.status() == CoreLiquidationState.Status.INSURANCE_REQUIRED)
                        claimReceipts[index] = value;
                }
                return null;
            }
            runtime.replaceLiquidation(nextLiquidation);
            return null;
        }

        /**
         * Owner 非阻塞等索赔读取、派发并等待账本，再派发源 Lane 的强平状态更新；任一阶段未就绪都返回 false，全部完成才收尾。
         */
        @Override public boolean getAsBoolean() {
            if (done) return true;
            if (capturingClaims) {
                if (!runtime.pollControlLanes()) return false;
                capturingClaims = false;
            }
            if (!ledgerDispatched) {
                if (!runtime.dispatchLedgerCommand(this)) return false;
                ledgerDispatched = true;
            }
            if (!ledgerCommandCompleted()) return false;
            if (!accountsDispatched) {
                runtime.dispatchControlLanes(laneMask, this);
                accountsDispatched = true;
            }
            if (!runtime.pollControlLanes()) return false;
            finish();
            return true;
        }

        /**
         * 成功后增加全局修订号，释放本批索赔和命令引用，并标记续步完成供命令槽回收。
         */
        private void finish() {
            if (done) return;
            runtime.setMetadata(runtime.productLine(), Math.incrementExact(runtime.revision()));
            java.util.Arrays.fill(claimReceipts, 0, claimCount, null);
            allocation = null;
            liquidation = nextLiquidation = null;
            command = null;
            done = true;
        }
    }

    /**
     * 查找源强平关联的合约配置，缺失时返回 INSTRUMENT_NOT_FOUND 业务拒绝。
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param instrumentId 需要已经注册在当前运行时的外部合约编号
     */
    private static CoreInstrument requireInstrument(TradingRuntimeState runtime, String instrumentId) {
        // 源强平或当前候选的合约配置，用来确定并过滤结算资产。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }
}
