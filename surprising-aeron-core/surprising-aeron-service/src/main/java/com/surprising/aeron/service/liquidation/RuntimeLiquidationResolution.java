package com.surprising.aeron.service.liquidation;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.protocol.ResolveLiquidationCommand;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import com.surprising.aeron.service.treasury.InsuranceAllocationPolicy;
import com.surprising.aeron.service.treasury.SystemLedgerCommand;
import com.surprising.aeron.service.treasury.TreasuryRuntime;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;

/** 强平步骤 7：由后续 RESOLVE_LIQUIDATION 命令确认结果，按决议补亏或完成。 强平执行后的保险基金补亏与完成判定。先由账户 Lane 捕获同资产保险索赔事实，账本按确定顺序校验分配并扣保险余额和亏空，再交源账户 Lane 发布强平新状态；真正 ADL 走单独的减仓流程。 构造约定：只提供强平补亏入口，命令执行进度保存在可复用续步中。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class RuntimeLiquidationResolution {

    /**
     * 可回收的旧续步直接复用，否则新建，再准备本次索赔和补亏工作。
     * @param reuse 上次已经可回收的补亏续步，未提供或尚不可复用时新建
     * @param command 本次强平补亏决议，包含强平编号、保险或完成类型及声明覆盖金额
     * @param runtime 本次操作的交易运行时，各账户修改由所属 Lane 完成，资金总账由账本处理
     * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
     * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
     */
    public static ResolutionWork begin(ResolutionWork reuse, ResolveLiquidationCommand command,
            TradingAccountRuntime runtime, RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        if (command == null || runtime == null || identities == null || !runtime.productLine().isDerivative()) {
            throw new IllegalArgumentException("invalid liquidation resolution apply");
        }
        runtime.assertOwner();
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
        work.prepare(runtime, command, assetId);
        work.prepareClaimReads(candidateIds, identities);
        return work;
    }

    /**
     * 命令槽复用的强平补亏续步，按索赔读取、账本预算及记账、源账户更新的顺序推进；账本与账户都完成后才增加全局修订号。
     */
    public static final class ResolutionWork extends SystemLedgerCommand
            implements java.util.function.BooleanSupplier, java.util.function.IntFunction<Object> {
        /** 当前保险补亏或完成命令，成功结束时释放引用。 */
        private ResolveLiquidationCommand command;
        /** liquidation：源账户 Lane 捕获的待处理强平记录，账本根据它判断剩余亏空与阶段；nextLiquidation：账本根据实际覆盖额生成的新强平记录，随后交回源账户 Lane 写入。 */
        private LiquidationRuntime liquidation, nextLiquidation;
        /** 源合约结算资产整数编号，保险池、索赔和亏空必须使用同一资产。 */
        private int assetId;
        /** done：账本和源账户写入都完成后的标记，重复轮询直接返回完成；accountsDispatched：账本完成后是否已经派发源账户新状态写入。 */
        private boolean done, accountsDispatched;
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
         * 绑定本次命令，预检查修订号容量，重置读取、账本和源账户写入阶段；源强平由其所属 Lane 捕获。
         */
        private void prepare(TradingAccountRuntime runtime, ResolveLiquidationCommand command, int assetId) {
            initializeLedgerCommand(runtime);
            runtime.requireRevisionCapacity();
            this.command = command;
            this.assetId = assetId;
            liquidation = nextLiquidation = null;
            done = accountsDispatched = capturingClaims = false;
        }

        /**
         * 保险补亏时收集同结算资产候选强平的编号与 Lane，按需扩容复用数组，再一次派发源强平和候选索赔读取；其他决议只读取源强平。
         * @param candidateIds 参与保险分配的候选强平编号，准备阶段按源强平结算资产过滤
         * @param identities 币对、资产及持仓业务编号与运行时整数键的映射
         */
        private void prepareClaimReads(Iterable<Long> candidateIds, RuntimeIdentityRegistry identities) {
            claimCount = 0;
            // 索赔读取阶段需要访问的所有账户 Lane 位集合，始终包含源强平的 Lane。
            long readMask = runtime.topology().accountLaneMask(sourceUserId);
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
                    InsuranceAllocationPolicy.Resolution result = InsuranceAllocationPolicy.resolve(
                            claimReceipts, claimCount, liquidation.liquidationId(), assetId, ledger);
                    if (!result.next()) throw new CoreStateRejectedException("INSURANCE_RESOLUTION_ORDER_MISMATCH",
                            "insurance claims must resolve in deterministic priority order");
                    if (command.coveredUnits() != result.expectedCoverage())
                        throw new CoreStateRejectedException("INSURANCE_ALLOCATION_MISMATCH", "insurance coverage does not match deterministic allocation");
                    if (command.coveredUnits() > ledger.insurance(assetId))
                        throw new CoreStateRejectedException("INSUFFICIENT_AVAILABLE_BALANCE", "insurance fund balance is insufficient");
                    nextDeficit = Math.subtractExact(nextDeficit, command.coveredUnits());
                    nextStatus = nextDeficit == 0 ? CoreLiquidationState.Status.COMPLETED : CoreLiquidationState.Status.ADL_REQUIRED;
                    if (command.coveredUnits() != 0) {
                        ledger.setInsurance(assetId,
                                Math.subtractExact(ledger.insurance(assetId), command.coveredUnits()),
                                Math.subtractExact(ledger.insuranceDeficit(assetId), command.coveredUnits()));
                    }
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
            if (!accountsDispatched) {
                runtime.dispatchLedgerCommand(this);
                ledgerCommandCompleted();
                runtime.dispatchControlLanes(runtime.topology().accountLaneMask(sourceUserId), this);
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
    private static CoreInstrument requireInstrument(TradingAccountRuntime runtime, String instrumentId) {
        // 源强平或当前候选的合约配置，用来确定并过滤结算资产。
        CoreInstrument instrument = runtime.instrument(instrumentId);
        if (instrument == null) {
            throw new CoreStateRejectedException("INSTRUMENT_NOT_FOUND", "instrument state is missing");
        }
        return instrument;
    }
}
