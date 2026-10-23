package com.surprising.aeron.service.treasury;

import com.surprising.aeron.model.CoreLiquidationState;
import com.surprising.aeron.service.core.TradingAccountRuntime;
import com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.risk.LiquidationRuntime;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;

/** 按结算资产共享保险预算的确定性比例分配。先按触发价格序号、用户、合约、持仓方向及强平编号排序，以大整数计算各缺口比例，整除后的余数按该固定顺序逐项补一。只计算补偿计划，不在本类扣保险或更新强平记录；候选唯一性及跨线程引用生命周期由调用方保证。 构造约定：只提供保险索赔分组和分配静态入口，不允许单独实例化。 */
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public final class InsuranceAllocationPolicy {

    /** 运行时索赔固定顺序：触发价格序号、用户、业务合约名、持仓方向 ordinal、强平编号。 */
    private static final Comparator<Claim> CLAIM_ORDER = Comparator
            .comparingLong((/* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ Claim claim) -> claim.liquidation().triggerPriceSequence())
            .thenComparingLong(/* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim -> claim.liquidation().userId())
            .thenComparing(Claim::instrumentId)
            .thenComparingInt(/* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim -> claim.liquidation().positionSide().ordinal())
            .thenComparingLong(/* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim -> claim.liquidation().liquidationId());

    /**
     * 在原运行时可读边界准备候选索赔，再通过静默账本查询读取各资产保险预算并计算全部分配，不直接从 Owner 读取可变财库。
     * @param runtime 提供当前可读取强平事实及账本查询边界的运行时
     * @param identities 把运行时币对及结算资产转换为业务名称或稠密编号的字典
     * @param candidateIds 上游提供的候选强平编号，允许空项但本类不去重
     */
    public static Map<Long, Long> allocations(TradingAccountRuntime runtime,
                                               RuntimeIdentityRegistry identities,
                                               Iterable<Long> candidateIds) {
        if (runtime == null || identities == null || candidateIds == null) {
            throw new IllegalArgumentException("insurance allocation inputs are required");
        }
        // 本次准备或合并的索赔输入，包含当前调用持有的列表引用。
        AllocationInputs inputs = prepareAllocations(runtime, identities, candidateIds);
        return runtime.queryLedgerAtRest(/* 账本静默查询回调持有的原财库，预算由该执行边界读取。 */ ledger -> allocations(ledger, inputs));
    }

    /** 当前命令或查询持有的按资产索赔引用分组，预算由账本读取；列表不是永久不可变副本，后续分配会原地排序，生命周期由调用方交接。 构造约定：接管当前命令或查询已准备的按资产索赔列表，不复制列表或权威账户状态。 参数 byAsset：当前调用独占的按资产索赔列表 Map，构造时接管引用 */
    @RequiredArgsConstructor(access = AccessLevel.PRIVATE)
    public static final class AllocationInputs {
        /** 当前命令或查询按结算资产保存的索赔列表，后续分配会原地排序，消费期间独占。 */
        private final Map<Integer, ArrayList<Claim>> byAsset;
    }

    /**
     * Owner 读取候选，只保留需要保险且规范币对仍匹配的强平记录，按结算资产分组；空编号或不适用记录跳过，不在此按编号去重。
     * @param runtime 提供当前可读取强平事实及账本查询边界的运行时
     * @param identities 把运行时币对及结算资产转换为业务名称或稠密编号的字典
     * @param candidateIds 上游提供的候选强平编号，允许空项但本类不去重
     */
    public static AllocationInputs prepareAllocations(TradingAccountRuntime runtime,
            RuntimeIdentityRegistry identities, Iterable<Long> candidateIds) {
        if (runtime == null || identities == null || candidateIds == null)
            throw new IllegalArgumentException("insurance allocation inputs are required");
        runtime.assertOwner();
        // 本次新建的结算资产到索赔列表分组。
        Map<Integer, ArrayList<Claim>> byAsset = new HashMap<>();
        for (Long /* 当前读取的可空候选强平编号。 */ candidateId : candidateIds) {
            if (candidateId == null) continue;
            // 当前运行时或冷状态读取到的强平记录。
            LiquidationRuntime liquidation = runtime.liquidation(candidateId);
            if (liquidation == null || liquidation.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
                continue;
            }
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = runtime.instrument(identities.instrumentId(liquidation.symbolId()));
            if (instrument == null || instrument != liquidation.instrument()) continue;
            // 当前索赔所属结算资产编号。
            int assetId = identities.assetId(instrument.settleAsset());
            byAsset.computeIfAbsent(assetId, /* 创建该资产分组时回调收到的键，只需要新建列表，不再读取键。 */ ignored -> new ArrayList<>())
                    .add(new Claim(liquidation, identities.instrumentId(liquidation.symbolId())));
        }
        return new AllocationInputs(byAsset);
    }

    /**
     * 在已有账户 Lane 命令作用域读取对应强平记录，按上游配好的资产数组分组，跳过缺失、非保险阶段或币对对象不符项；数组长度及资产配对由调用方保证。
     * @param runtime 提供当前可读取强平事实及账本查询边界的运行时
     * @param ids 当前 Lane 需要读取的强平编号数组
     * @param assetIds 与强平编号同下标且由上游配好的结算资产编号数组
     * @param instruments 与强平编号同下标的规范合约输入数组
     */
    public static AllocationInputs prepareLaneAllocations(TradingAccountRuntime runtime, long[] ids,
            int[] assetIds, CoreInstrument[] instruments) {
        if (runtime.currentLaneCommandScope() == null) throw new IllegalStateException("insurance claim read requires Lane ownership");
        // 本次新建的结算资产到索赔列表分组。
        Map<Integer, ArrayList<Claim>> byAsset = new HashMap<>();
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < ids.length; index++) {
            // 当前 Lane 回执或运行时查到的强平值，空值跳过。
            LiquidationRuntime value = runtime.liquidation(ids[index]);
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = instruments[index];
            if (value == null || value.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED
                    || instrument == null || instrument != value.instrument()) continue;
            byAsset.computeIfAbsent(assetIds[index], /* 创建该资产分组时回调收到的键，只需要新建列表，不再读取键。 */ ignored -> new ArrayList<>()).add(new Claim(value, instrument.instrumentId()));
        }
        return new AllocationInputs(byAsset);
    }

    /**
     * 把各 Lane 已交接的索赔列表按资产拼到新输入容器，跳过空 Lane 输入，不复制索赔记录或重新去重。
     * @param laneClaims 各 Lane 已交回并完成所有权交接的索赔输入数组
     */
    public static AllocationInputs mergeLaneClaims(AllocationInputs[] laneClaims) {
        // 合并各 Lane 分组后新建的资产索赔 Map。
        Map<Integer, ArrayList<Claim>> merged = new HashMap<>();
        for (AllocationInputs /* 本次准备或合并的索赔输入，包含当前调用持有的列表引用。 */ inputs : laneClaims) if (inputs != null)
            inputs.byAsset.forEach((/* 当前索赔所属结算资产编号。 */ assetId, /* 当前资产需要分配的独占索赔列表，排序会改变列表顺序。 */ claims) -> merged.computeIfAbsent(assetId, /* 创建该资产分组时回调收到的键，只需要新建列表，不再读取键。 */ ignored -> new ArrayList<>()).addAll(claims));
        return new AllocationInputs(merged);
    }

    /**
     * 当前账本所有者读取每组资产保险余额，再计算该组全部强平编号的补偿金额，返回新结果 Map。
     * @param ledger 当前查询或执行方持有的原系统财库
     * @param inputs 当前调用独占的索赔及目标输入，分配会原地排序其中列表
     */
    public static Map<Long, Long> allocations(TreasuryRuntime ledger, AllocationInputs inputs) {
        // 本次新建的强平编号到预期补偿结果 Map。
        Map<Long, Long> result = new HashMap<>();
        inputs.byAsset.forEach((/* 当前索赔所属结算资产编号。 */ assetId, /* 当前资产需要分配的独占索赔列表，排序会改变列表顺序。 */ claims) -> allocateAsset(ledger.insurance(assetId), claims, result));
        return result;
    }

    /**
     * 校验目标仍需保险且规范币对匹配，一遍收集同结算资产候选，随后在原账本查询边界同时计算优先顺序和目标份额；目标不适用返回非首位且零补偿。
     * @param runtime 提供当前可读取强平事实及账本查询边界的运行时
     * @param identities 把运行时币对及结算资产转换为业务名称或稠密编号的字典
     * @param candidateIds 上游提供的候选强平编号，允许空项但本类不去重
     * @param liquidationId 本次要查询保险份额及优先级的目标强平编号
     */
    public static Resolution resolve(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                      Iterable<Long> candidateIds, long liquidationId) {
        if (runtime == null || identities == null || candidateIds == null) {
            throw new IllegalArgumentException("insurance allocation inputs are required");
        }
        // 本次查询的目标强平记录，必须仍适用保险分配。
        LiquidationRuntime target = runtime.liquidation(liquidationId);
        if (target == null || target.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) {
            return new Resolution(false, 0);
        }
        // 目标强平对应的规范合约。
        CoreInstrument targetInstrument = runtime.instrument(identities.instrumentId(target.symbolId()));
        if (targetInstrument == null || targetInstrument != target.instrument()) {
            return new Resolution(false, 0);
        }
        // 当前索赔所属结算资产编号。
        int assetId = identities.assetId(targetInstrument.settleAsset());
        // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
        ArrayList<Claim> claims = new ArrayList<>();
        for (Long /* 当前候选列表中的可空强平编号。 */ id : candidateIds) {
            if (id == null) continue;
            // 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。
            LiquidationRuntime claim = runtime.liquidation(id);
            if (claim == null || claim.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) continue;
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = runtime.instrument(identities.instrumentId(claim.symbolId()));
            if (instrument != null && instrument == claim.instrument()
                    && identities.assetId(instrument.settleAsset()) == assetId) {
                claims.add(new Claim(claim, identities.instrumentId(claim.symbolId())));
            }
        }
        // 本次准备或合并的索赔输入，包含当前调用持有的列表引用。
        ResolutionInputs inputs = new ResolutionInputs(liquidationId, assetId, target.deficitUnits(), claims);
        return runtime.queryLedgerAtRest(/* 账本静默查询回调持有的原财库，预算由该执行边界读取。 */ ledger -> resolve(inputs, ledger));
    }

    /**
     * 按指定目标及结算资产准备当前可读候选索赔，只保留需要保险且规范对象匹配的记录，预算留给账本读取。
     * @param runtime 提供当前可读取强平事实及账本查询边界的运行时
     * @param identities 把运行时币对及结算资产转换为业务名称或稠密编号的字典
     * @param candidateIds 上游提供的候选强平编号，允许空项但本类不去重
     * @param target 本次目标强平事实，缺口及身份由既有输入边界固定
     * @param assetId 目标共享保险预算的结算资产编号
     */
    public static ResolutionInputs prepareResolution(TradingAccountRuntime runtime, RuntimeIdentityRegistry identities,
                                               Iterable<Long> candidateIds, LiquidationRuntime target, int assetId) {
        // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
        ArrayList<Claim> claims = new ArrayList<>();
        for (Long /* 当前候选列表中的可空强平编号。 */ id : candidateIds) {
            if (id == null) continue;
            // 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。
            LiquidationRuntime claim = runtime.liquidation(id);
            if (claim == null || claim.status() != CoreLiquidationState.Status.INSURANCE_REQUIRED) continue;
            // 当前候选对应的规范合约对象，需与强平输入中的对象匹配。
            CoreInstrument instrument = runtime.instrument(identities.instrumentId(claim.symbolId()));
            if (instrument != null && instrument == claim.instrument()
                    && identities.assetId(instrument.settleAsset()) == assetId)
                claims.add(new Claim(claim, identities.instrumentId(claim.symbolId())));
        }
        return new ResolutionInputs(target.liquidationId(), assetId, target.deficitUnits(), claims);
    }

    /**
     * 消费当前命令已经按业务筛选的 Lane 回执有效前缀，跳过空回执并保存引用；状态、资产及有效数量配对由上游保证，此处不重复筛选。
     * @param receipts 上游已按业务范围筛选的 Lane 强平回执数组
     * @param count 回执数组当前有效前缀长度
     * @param target 本次目标强平事实，缺口及身份由既有输入边界固定
     * @param assetId 目标共享保险预算的结算资产编号
     */
    public static ResolutionInputs prepareResolution(LiquidationRuntime[] receipts, int count,
                                               LiquidationRuntime target, int assetId) {
        // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
        ArrayList<Claim> claims = new ArrayList<>();
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < count; index++) {
            // 当前 Lane 回执或运行时查到的强平值，空值跳过。
            LiquidationRuntime value = receipts[index];
            if (value != null) claims.add(new Claim(value, value.instrument().instrumentId()));
        }
        return new ResolutionInputs(target.liquidationId(), assetId, target.deficitUnits(), claims);
    }

    /**
     * 目标强平身份、资产、缺口及当前候选列表，record 不防御复制 ArrayList，解析期间由本调用独占。
     * @param liquidationId 本次要查询保险份额及优先级的目标强平编号
     * @param assetId 目标共享保险预算的结算资产编号
     * @param deficitUnits 本次目标输入中记录的缺口金额
     * @param claims 当前调用独占的待保险索赔列表，分配方法会原地排序
     */
    public record ResolutionInputs(long liquidationId, int assetId, long deficitUnits, ArrayList<Claim> claims) { }

    /**
     * 原地排序当前独占索赔列表并累计缺口，目标存在才按保险非负预算计算比例；预算足够用目标输入缺口，不足时整除并按目标排序位置补余数，同时返回是否首位。
     * @param inputs 当前调用独占的索赔及目标输入，分配会原地排序其中列表
     * @param ledger 当前查询或执行方持有的原系统财库
     */
    public static Resolution resolve(ResolutionInputs inputs, TreasuryRuntime ledger) {
        // 当前资产需要分配的独占索赔列表，排序会改变列表顺序。
        ArrayList<Claim> claims = inputs.claims();
        if (claims.isEmpty()) return new Resolution(false, 0);
        claims.sort(CLAIM_ORDER);
        // 目标在固定排序中的下标，负一表示目标不在当前索赔列表。
        int targetIndex = -1;
        // 使用 BigInteger 汇总的同资产总缺口，避免长整数总额溢出。
        BigInteger total = BigInteger.ZERO;
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < claims.size(); index++) {
            // 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。
            Claim claim = claims.get(index);
            total = total.add(BigInteger.valueOf(claim.liquidation().deficitUnits()));
            if (claim.liquidation().liquidationId() == inputs.liquidationId()) targetIndex = index;
        }
        if (targetIndex < 0 || total.signum() == 0) return new Resolution(false, 0);
        // 当前非负保险余额与同资产总缺口取小后的可分配预算。
        BigInteger available = BigInteger.valueOf(Math.max(0, ledger.insurance(inputs.assetId()))).min(total);
        if (available.signum() == 0) return new Resolution(targetIndex == 0, 0);
        if (available.equals(total)) return new Resolution(targetIndex == 0, inputs.deficitUnits());
        // 已按比例整除分出的基础金额大整数合计。
        BigInteger allocated = BigInteger.ZERO;
        // 目标按比例整除得到的基础补偿，不含余数补一。
        long targetBase = 0;
        for (Claim /* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim : claims) {
            // 当前索赔按预算乘缺口再除总缺口得到的基础份额。
            BigInteger share = available.multiply(BigInteger.valueOf(claim.liquidation().deficitUnits()))
                    .divide(total);
            allocated = allocated.add(share);
            if (claim.liquidation().liquidationId() == inputs.liquidationId()) targetBase = share.longValueExact();
        }
        // 预算减基础份额合计后的剩余单位数，按固定排序前缀逐项补一。
        int remainder = available.subtract(allocated).intValueExact();
        return new Resolution(targetIndex == 0, targetBase + (targetIndex < remainder ? 1 : 0));
    }

    /**
     * 目标是否应先处理，以及当前预算下预计保险补偿的计算结果，不代表资金已经支付。
     * @param next 目标是否为同资产确定性顺序首项
     * @param expectedCoverage 按当前预算和候选缺口计算的目标补偿金额
     */
    public record Resolution(boolean next, long expectedCoverage) { }

    /**
     * 对单资产索赔原地排序，以非负预算和总缺口取小，按大整数比例整除并把剩余单位按排序前缀补一，写入每个强平编号的结果。
     * @param availableUnits 此资产当前可分配保险预算
     * @param claims 当前调用独占的待保险索赔列表，分配方法会原地排序
     * @param result 接收强平编号到补偿金额的调用方结果 Map
     */
    private static void allocateAsset(long availableUnits, ArrayList<Claim> claims, Map<Long, Long> result) {
        claims.sort(CLAIM_ORDER);
        // 使用 BigInteger 汇总的同资产总缺口，避免长整数总额溢出。
        BigInteger total = BigInteger.ZERO;
        for (Claim /* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim : claims) total = total.add(BigInteger.valueOf(claim.liquidation().deficitUnits()));
        // 当前非负保险余额与同资产总缺口取小后的可分配预算。
        BigInteger available = BigInteger.valueOf(Math.max(0, availableUnits)).min(total);
        if (available.signum() == 0) {
            for (Claim /* 当前参与排序、筛选或比例计算的强平索赔记录，比较字段取业务事实。 */ claim : claims) result.put(claim.liquidation().liquidationId(), 0L);
            return;
        }
        // 当前资产每个已排序索赔的基础比例补偿原语数组。
        long[] base = new long[claims.size()];
        // 已按比例整除分出的基础金额大整数合计。
        BigInteger allocated = BigInteger.ZERO;
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < claims.size(); index++) {
            // 当前索赔按预算乘缺口再除总缺口得到的基础份额。
            BigInteger share = available.multiply(BigInteger.valueOf(claims.get(index).liquidation().deficitUnits()))
                    .divide(total);
            base[index] = share.longValueExact();
            allocated = allocated.add(share);
        }
        // 预算减基础份额合计后的剩余单位数，按固定排序前缀逐项补一。
        int remainder = available.subtract(allocated).intValueExact();
        for (/* 当前候选、有效回执或已排序索赔列表下标。 */ int index = 0; index < claims.size(); index++) {
            // 当前索赔基础份额加上可能的一个余数单位后的最终补偿。
            long coverage = base[index] + (index < remainder ? 1 : 0);
            result.put(claims.get(index).liquidation().liquidationId(), coverage);
        }
    }

    /**
     * 当前调用持有的强平记录引用及业务合约名，供排序和比例计算使用，不另建权威强平或资金状态。
     * @param liquidation 当前命令持有的强平记录引用，不复制权威账户
     * @param instrumentId 索赔对应的业务合约名称，用于确定性排序
     */
    private record Claim(LiquidationRuntime liquidation, String instrumentId) {
    }
}
