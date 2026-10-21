package com.surprising.aeron.service.orchestration.replay;

import com.surprising.aeron.service.state.TradingCoreState;
import com.surprising.aeron.service.state.math.CoreContractMath;
import com.surprising.aeron.service.state.math.OptionContractMath;
import com.surprising.product.api.ProductLine;
import java.math.BigInteger;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * 独立 Archive 回放中的只读权益物化结果，不属于在线 Matcher 或 Account Lane。按资产保存用户余额加持仓权益：期权用市值，其余衍生品用未实现盈亏，金额以十进制字符串传输。调用方先证明回放前缀覆盖目标时刻，才可持久化日初或分钟切点；本类只校验时间参数，不能自行证明前缀完整。
 * @param productLine 当前独立回放状态所属产品线
 * @param asOfEpochMillis 正的权益观察时刻
 * @param sourceTimestamp 已提交状态所到达的来源时间，不得晚于观察时刻
 * @param users 按用户号索引的权益结果，构造时复制外层 Map
 */
public record CommittedPortfolioSnapshot(ProductLine productLine, long asOfEpochMillis,
                                         long sourceTimestamp, Map<Long, UserEquity> users) {
    /**
     * 校验产品线及来源/观察时间边界，复制固定外层用户 Map；不会把字符串金额再解析或重算用户权益。
     */
    public CommittedPortfolioSnapshot {
        if (productLine == null || asOfEpochMillis <= 0 || sourceTimestamp < 0 || sourceTimestamp > asOfEpochMillis)
            throw new IllegalArgumentException("invalid committed portfolio time");
        users = Map.copyOf(users);
    }

    /**
     * 从当前已提交状态按用户聚合可用加冻结余额，用 BigInteger 保留合计精度，再按产品线计算各非零持仓的 long 权益增量。缺少品种/标记、价格过期/来自未来或单持仓算术溢出时标为不完整；仍可能保留部分数值，调用方必须看 complete。待划转用户即使还无账户也新增不完整空表，读取不修改权威状态。
     * @param state 已建立完整回放边界的只读交易状态
     * @param sourceTimestamp 已提交状态所到达的来源时间，不得晚于观察时刻
     * @param asOf 本次正的观察时刻
     * @param maximumMarkAgeMillis 允许的正标记价最大年龄，按观察时刻判断
     * @param pendingUsers 仍受未完成划转影响的用户号集合，已存在与未建账户用户均应不可用
     */
    static CommittedPortfolioSnapshot capture(TradingCoreState state, long sourceTimestamp,
                                              long asOf, long maximumMarkAgeMillis, Set<Long> pendingUsers) {
        if (state == null || pendingUsers == null || sourceTimestamp < 0 || asOf <= 0
                || sourceTimestamp > asOf || maximumMarkAgeMillis <= 0)
            throw new IllegalArgumentException("invalid committed portfolio boundary");
        // 按用户收集的临时权益结果，最终由 record 复制为不可修改 Map。
        var users = new TreeMap<Long, UserEquity>();
        state.users().forEach((/* 当前状态用户或待划转用户的编号。 */ userId, /* 当前只读用户状态，读取余额和非零持仓。 */ user) -> {
            // 当前用户按资产精确累计的 BigInteger 余额与可表示持仓增量。
            var units = new TreeMap<String, BigInteger>();
            user.balances().forEach((/* 当前余额或权益所在资产名称。 */ asset, /* 当前资产的可用与冻结余额，先分别转 BigInteger 再相加。 */ balance) -> units.put(asset,
                    BigInteger.valueOf(balance.availableUnits()).add(BigInteger.valueOf(balance.lockedUnits()))));
            // 该用户是否既不受待划转影响、又具有可用价格和可表示的全部持仓权益。
            boolean complete = !pendingUsers.contains(userId);
            for (var /* 当前用户的一个持仓，数量为零时直接跳过。 */ position : user.positions().values()) {
                if (position.signedQuantitySteps() == 0) continue;
                // 当前持仓对应的品种规格，缺少时用户不可用。
                var instrument = state.instruments().get(position.instrumentId());
                // 当前品种的价格快照，必须不晚于观察时刻且未超过年龄上限。
                var mark = state.riskState().markPrices().get(position.instrumentId());
                if (instrument == null || mark == null || mark.generatedAtEpochMillis() > asOf
                        || asOf - mark.generatedAtEpochMillis() > maximumMarkAgeMillis) {
                    complete = false;
                    continue;
                }
                try {
                    // 期权市值或其他衍生品未实现盈亏的 long 单位增量，溢出由当前用户降为不完整。
                    long equityDelta = instrument.contractType().isOption()
                            ? OptionContractMath.optionMarketValueUnits(instrument,
                                    position.signedQuantitySteps(), mark.markPriceTicks())
                            : CoreContractMath.pnlUnits(instrument, position.signedQuantitySteps(),
                                    position.entryPriceTicks(), mark.markPriceTicks());
                    units.merge(position.marginAsset(), BigInteger.valueOf(equityDelta), BigInteger::add);
                } catch (/* 单笔持仓权益无法在 long 内表示的算术异常，不让部分结果冒充完整权益。 */ ArithmeticException unrepresentablePosition) {
                    // 这类单持仓算术溢出只使该用户权益不可用；保留的部分值不能冒充完整结果。
                    complete = false;
                }
            }
            // 把当前精确整数逐资产转为十进制字符串的临时 Map。
            var exact = new TreeMap<String, String>();
            units.forEach((/* 当前余额或权益所在资产名称。 */ asset, /* 当前资产已累计的精确整数金额。 */ amount) -> exact.put(asset, amount.toString()));
            users.put(userId, new UserEquity(complete, exact));
        });
        // 收款人在当前产品线可能还没建账户；划转未完成时，不能把缺少账户当作零权益。
        pendingUsers.forEach(/* 当前状态用户或待划转用户的编号。 */ userId -> users.putIfAbsent(userId, new UserEquity(false, Map.of())));
        return new CommittedPortfolioSnapshot(state.productLine(), asOf, sourceTimestamp, users);
    }

    /**
     * 一个用户的完整性标记及单资产金额文本。Map 固定不可修改，完整性为 false 时可能含余额或部分持仓值，不能据此发布完整净权益。
     * @param complete 当前用户全部权益是否可用，false 时数值只可视为不完整结果
     * @param netAssetUnits 资产名称到精确十进制单位字符串的 Map
     */
    public record UserEquity(boolean complete, Map<String, String> netAssetUnits) {
        /**
         * 固定复制资产到金额文本的 Map，不额外验证文本是否为数值；complete 为 false 时这些值不能解释为完整净权益。
         */
        public UserEquity { netAssetUnits = Map.copyOf(netAssetUnits); }
    }
}
