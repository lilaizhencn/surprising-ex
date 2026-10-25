package com.surprising.aeron.service.query;

import com.surprising.aeron.bootstrap.account.SettlementLaneWorker;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.account.AccountLaneState;
import com.surprising.aeron.service.core.TradingCoreRuntime;
import com.surprising.aeron.service.query.state.RuntimeOperationalQueryService;
import com.surprising.aeron.service.query.state.RuntimeRiskQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * 一次风险快照或 ADL 候选查询的线程交接对象：Owner 固定市场输入，Ledger 选择有界路由，各账户 Lane 读取自己的状态，最后完成的 Lane 合并并编码响应。只保留本次路由和查询 DTO，不建立账户镜像。
 */
final class RiskAccountQuery implements SettlementLaneWorker.Command, Function<AccountLaneState, Void>,
        com.surprising.aeron.service.treasury.SystemLedgerLane.Entry {
    /** 提供账本、账户投递及查询结果登记的所属运行时，不代表任意线程都能直接访问其账户。 */
    private final TradingCoreRuntime owner;
    /** 本次查询的内部编号，完成响应使用它写入结果表供 Owner 消费。 */
    final long id;
    /**
     * commandCount：进入查询时固定的已应用命令计数，作为返回响应的计数边界。
     * requestedUser：风险查询指定的用户编号，0 表示按持仓成员选择多个用户。
     */
    private final long commandCount, requestedUser;
    /** 账本路由阶段计算的目标账户 Lane 位集合，账户只在该集合内执行。 */
    private long laneMask;
    /**
     * users：账本选出的用户编号数组；风险路由按用户选择，ADL 中每个位置对应一份候选持仓，用户可重复。
     * positionKeys：与 users 对齐的 ADL 持仓身份数组；null 表示按用户生成风险快照。
     */
    private long[] users, positionKeys;
    /** 账本完成路由或直接回复后发布的可见性标记，Owner 读到 true 才使用路由及直接回复状态。 */
    private volatile boolean routesReady;
    /** 账本阶段已经保存空结果或拒绝响应，随 routesReady 发布后 Owner 不再投递账户查询。 */
    private boolean ledgerResponded;
    /** ADL 查询的规范结算资产，风险快照查询用 null 区分分支。 */
    private final String asset;
    /** 每个 Lane 保留及最终合并的 ADL 候选上限，不放宽索引扫描规模限制。 */
    private final int limit;
    /** 进入查询时固定的市场输入，不含余额或持仓副本。 */
    private final RuntimeRiskQueryService.MarketInputs markets;
    /** 按 Lane 编号存放的风险快照 DTO 列表，各 Lane 只写自己的列表，全部完成后统一归并。 */
    private final List<ArrayList<CoreRiskSnapshotView>> riskResults;
    /** 各 Lane 独写的有界 ADL 候选列表，最后再按统一优先级合并。 */
    private final List<ArrayList<CoreAdlCandidateView>> adlResults;
    /** 每个 Lane 的业务失败码槽；全部完成后按 Lane 编号选择先遇到的失败。 */
    private final CoreResultCode[] failures;
    /** 原子累计已完成的账户 Lane 位，保证只有最后一个完成者编码响应，并检测重复回执。 */
    private final AtomicLong completedLaneMask = new AtomicLong();

    /**
     * 在 Owner 解码查询类型及 ADL 条件，固定当前命令计数和市场输入，创建稍后交给 Ledger 选择账户路由的查询对象。
     * @param owner 所属交易运行时，提供索引路由、查询登记和线程交接入口
     * @param message 风险状态或 ADL 候选查询消息，用户条件取自消息头，ADL 条件取自载荷
     * @param id 本次查询的内部异步编号，与外部命令 UUID 分开登记
     */
    static RiskAccountQuery prepare(TradingCoreRuntime owner, CoreMessage message, long id) {
        // 本次 ADL 资产筛选条件，风险快照查询保持 null。
        String asset = null;
        // 从 ADL 载荷读取的返回条数上限，风险查询保持零。
        int limit = 0;
        if (message.header().messageType() != CoreMessageType.RISK_STATE_QUERY) {
            // 已解码的 ADL 资产与候选数量条件，用于校验并规范化资产文本。
            var query = CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
            asset = AssetBalance.normalizeAsset(query.asset());
            limit = query.limit();
        }
        return new RiskAccountQuery(owner, id, owner.appliedCommandCount(), message.header().userId(), asset, limit,
                RuntimeRiskQueryService.MarketInputs.capture(owner.runtimeState(), owner.identities(), owner.runtimeState().instrumentIdsForQuery()));
    }

    /**
     * 保存本次查询条件，并按原有 Lane 数量分配各 Lane 独写的风险结果、ADL 候选及失败槽，等待账本填好路由。
     * @param owner 所属交易运行时，提供索引路由、查询登记和线程交接入口
     * @param id 本次查询的内部异步编号，与外部命令 UUID 分开登记
     * @param commandCount 查询进入时固定的已应用命令计数，完成响应沿用此值
     * @param requestedUser 风险查询的用户条件，0 表示按持仓成员查询全部用户
     * @param asset 规范化的 ADL 结算资产；null 表示本次为风险快照查询
     * @param limit ADL 最多返回的候选数量，风险快照查询不使用此限制
     * @param markets Owner 在进入查询时固定的币对配置与标记价输入，后续账户读取共用它
     */
    private RiskAccountQuery(TradingCoreRuntime owner, long id, long commandCount,
            long requestedUser, String asset, int limit, RuntimeRiskQueryService.MarketInputs markets) {
        this.owner = owner; this.id = id; this.commandCount = commandCount; this.requestedUser = requestedUser;
        this.asset = asset; this.limit = limit; this.markets = markets;
        // 当前账户 Lane 总数，决定结果和错误槽的固定布局。
        int count = owner.runtimeState().topology().accountLaneCount();
        failures = new CoreResultCode[count];
        riskResults = new ArrayList<>(count); adlResults = new ArrayList<>(count);
        for (/* 正在初始化查询结果列表的账户 Lane 编号。 */ int lane = 0; lane < count; lane++) {
            riskResults.add(new ArrayList<>());
            adlResults.add(new ArrayList<>(Math.min(limit, 64)));
        }
    }

    /**
     * 在 Ledger 线程按风险用户索引或 ADL 持仓索引选择有界路由，计算需要访问的账户 Lane；无路由或参数、规模错误就直接保存响应，最后发布就绪位并通知 Owner。
     * @param ignored 账本任务接口传入的 TreasuryRuntime，本查询通过账本所属的持仓索引选路由，无须直接读该参数
     */
    @Override public void apply(com.surprising.aeron.service.treasury.TreasuryRuntime ignored) {
        try {
            if (asset == null) {
                // 按指定用户或全部持仓用户取得的风险路由，已受索引扫描上限约束。
                users = owner.positionUserIndex().riskQueryUserIds(requestedUser,
                        RuntimeOperationalQueryService.MAX_INDEX_SCAN);
            } else {
                // 指定结算资产下的非零持仓候选路由，每项都要保留用户及持仓身份。
                var routes = owner.adlPositionIndex().positions(asset, RuntimeOperationalQueryService.MAX_INDEX_SCAN);
                users = new long[routes.size()]; positionKeys = new long[routes.size()];
                // 写入 ADL 用户数组和持仓身份数组的共同下标，保证两数组对齐。
                int index = 0;
                for (var /* 当前 ADL 候选的用户、币对及持仓腿路由，用于构造精确持仓键。 */ route : routes) {
                    users[index] = route.userId();
                    positionKeys[index++] = com.surprising.aeron.service.core.identity.RuntimeIdentityRegistry.positionIdentityKey(
                            route.userId(), route.instrumentId(), route.positionSide());
                }
            }
            for (long /* 当前已选路由的用户编号，用来累计其所属账户 Lane 位。 */ user : users) laneMask |= owner.runtimeState().topology().accountLaneMask(user);
            if (laneMask == 0) {
                owner.bookQueries.completedBookQueries.put(id, CoreQueryService.CompletedBookQuery.encoded(completedResponse()));
                ledgerResponded = true;
            }
        } catch (/* 账本选择路由时发现扫描规模超限，转换为查询响应过大。 */ RuntimeOperationalQueryService.QueryTooLargeException failure) {
            failRoutes(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (/* 账本选择路由时遇到的非法查询条件，转换为 INVALID_COMMAND。 */ IllegalArgumentException failure) { failRoutes(CoreResultCode.INVALID_COMMAND); }
        routesReady = true;
    }

    /**
     * 路由阶段已经失败，无须再投递账户查询；保存带业务错误码的已编码响应，让 Owner 按普通查询结果消费。
     * @param code 需要返回的参数错误或查询规模超限等业务错误码
     */
    private void failRoutes(CoreResultCode code) {
        ledgerResponded = true;
        owner.bookQueries.completedBookQueries.put(id, CoreQueryService.CompletedBookQuery.encoded(rejected(code)));
    }

    /**
     * 由 Owner 等待账本发布路由，再把同一查询投到所选账户 Lane；已由账本直接回复则无需投递，邮箱暂不能接受时返回 false 供后续继续尝试。
     */
    boolean dispatchAccounts() {
        if (!routesReady) return false;
        if (ledgerResponded) return true;
        return owner.runtimeState().dispatchAccountQuery(laneMask, this);
    }

    /**
     * 在所属 Lane 的合法只读范围执行查询，把可预期的参数或规模错误记入本 Lane 槽；原子标记完成，只有最后完成的 Lane 合并编码并通知 Owner，重复完成视为内部错误。
     * @param lane 当前执行查询的账户 Lane，只能读取属于它的用户及持仓
     */
    @Override public void execute(AccountLaneState lane) {
        try { owner.runtimeState().readAccountLane(lane, this); }
        catch (/* 当前 Lane 生成风险结果时超过实体数量限制，记录在本 Lane 的失败槽。 */ RuntimeOperationalQueryService.QueryTooLargeException failure) {
            failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
        } catch (/* 当前 Lane 估值时发现非法参数，记录在本 Lane 的失败槽。 */ IllegalArgumentException failure) {
            failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
        }
        // 当前完成 Lane 的唯一位标记，不能重复发布同一位。
        long bit = 1L << lane.laneId();
        // 本轮原子更新前已完成的 Lane 集合；CAS 失败就重新读取。
        // 本轮把当前 Lane 合入后的集合，等于目标集合时才可归并最终结果。
        long previous, complete;
        do {
            previous = completedLaneMask.get(); complete = previous | bit;
            if ((previous & bit) != 0) throw new IllegalStateException("risk query Lane completed twice");
        } while (!completedLaneMask.compareAndSet(previous, complete));
        if (complete != laneMask) return;
        // 最后完成的 Lane 生成的成功或业务拒绝响应，存入异步结果表。
        CoreResponse response;
        try { response = completedResponse(); }
        catch (/* 最终归并时发现总实体数超限，返回 QUERY_RESPONSE_TOO_LARGE。 */ RuntimeOperationalQueryService.QueryTooLargeException failure) {
            response = rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (/* 最终编码或合并时遇到非法参数，返回 INVALID_COMMAND。 */ IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        owner.bookQueries.completedBookQueries.put(id, CoreQueryService.CompletedBookQuery.encoded(response));
    }

    /**
     * 在账户只读范围内过滤属于当前 Lane 的用户或持仓：风险查询重算用户快照，ADL 查询估值并保留有界候选；这里只写本 Lane 的 DTO 列表，不改账户。
     * @param lane 当前执行查询的账户 Lane，只能读取属于它的用户及持仓
     */
    @Override public Void apply(AccountLaneState lane) {
        // 用户到 Lane 的既定拓扑，保证当前消费者只访问自己的账户。
        var topology = owner.runtimeState().topology();
        if (positionKeys == null) {
            // 当前 Lane 独写的风险快照列表，逐用户追加前检查本 Lane 上限。
            var result = riskResults.get(lane.laneId());
            for (long /* 风险查询路由中的用户，属于其他 Lane 时直接跳过。 */ user : users) {
                if (topology.accountLaneId(user) != lane.laneId()) continue;
                // 对当前用户和固定市场输入重新估值后得到的风险快照 DTO。
                var rows = RuntimeRiskQueryService.userSnapshots(owner.runtimeState(), owner.identities(), user, markets);
                if (result.size() + rows.size() > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                    throw new RuntimeOperationalQueryService.QueryTooLargeException();
                result.addAll(rows);
            }
        } else {
            // 当前 Lane 独写的 ADL 优先候选列表，最多保留本次 limit 条。
            var result = adlResults.get(lane.laneId());
            for (/* 逐项读取对齐用户与持仓键的 ADL 路由下标。 */ int index = 0; index < users.length; index++) {
                if (topology.accountLaneId(users[index]) != lane.laneId()) continue;
                // 当前账户持仓估值后的 ADL 候选；不满足候选条件时为 null。
                var candidate = RuntimeRiskQueryService.adlCandidate(owner.runtimeState(), owner.identities(),
                        users[index], positionKeys[index], asset, markets);
                if (candidate != null) RuntimeRiskQueryService.addAdlCandidate(result, candidate, limit);
            }
        }
        return null;
    }

    /**
     * 按 Lane 编号优先返回已有失败；否则风险快照先检查合并数量，再按用户、币对文本及持仓腿排序编码，ADL 则归并各 Lane 的有界候选。空路由也使用相同响应协议。
     */
    CoreResponse completedResponse() {
        for (CoreResultCode /* 按 Lane 编号检查到的失败码，有失败就不返回部分成功列表。 */ failure : failures) if (failure != null) return rejected(failure);
        // 最终风险快照列表或 ADL 候选列表的协议编码字节。
        byte[] encoded;
        if (positionKeys == null) {
            // 各 Lane 风险 DTO 总数，先核对全局上限再分配合并列表。
            int count = 0;
            for (var /* 当前统计数量的 Lane 风险列表，不读取其账户权威对象。 */ rows : riskResults) count = Math.addExact(count, rows.size());
            if (count > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            // 容量已按总数准备的合并风险列表，仅用于排序及响应编码。
            var result = new ArrayList<CoreRiskSnapshotView>(count);
            for (var /* 正在按 Lane 顺序并入最终结果的风险 DTO 列表。 */ rows : riskResults) result.addAll(rows);
            result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                    .thenComparing(CoreRiskSnapshotView::instrumentId)
                    .thenComparingInt(/* 用户及币对相同时用于比较持仓腿枚举顺序的风险快照项。 */ value -> value.positionSide().ordinal()));
            encoded = CoreRiskQueryCodec.encode(result);
        } else encoded = CoreAdlQueryCodec.encodeCandidates(RuntimeRiskQueryService.mergeAdlCandidates(adlResults, limit));
        return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
    }

    /**
     * 以查询进入时的命令计数构造业务拒绝响应，不修改交易命令的结果账本或账户状态。
     * @param code 需要返回的参数错误或查询规模超限等业务错误码
     */
    private CoreResponse rejected(CoreResultCode code) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
    }
}
