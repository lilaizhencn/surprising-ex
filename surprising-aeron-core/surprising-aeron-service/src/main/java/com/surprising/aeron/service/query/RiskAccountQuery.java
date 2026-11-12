package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.model.AssetBalance;
import com.surprising.aeron.protocol.CoreAdlCandidateView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreRiskSnapshotView;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.account.AccountLaneCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.core.TradingCore;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** 一次风险快照或 ADL 候选查询的线程交接对象：Owner 固定市场输入，Ledger 选择有界路由，各账户 Lane 读取自己的状态，最后完成的 Lane 合并并编码响应。 */
final class RiskAccountQuery extends java.util.concurrent.CompletableFuture<CoreQueryService.QueryResult> implements AccountLaneCommand, Function<AccountPartition, Void> {
    /** 提供账本、账户投递及查询结果登记的所属运行时，不代表任意线程都能直接访问其账户。 */
    private final TradingCore owner;
    private final long commandCount, requestedUser;
    private long laneMask;
    private long[] users, positionKeys;
    private boolean accountsDispatched;
    private final String asset;
    private final int limit;
    private final MarketInputs markets;
    private final List<ArrayList<CoreRiskSnapshotView>> riskResults;
    private final List<ArrayList<CoreAdlCandidateView>> adlResults;
    private final CoreResultCode[] failures;
    private final AtomicLong completedLaneMask = new AtomicLong();

    /** 在 Owner 解码查询类型及 ADL 条件，固定当前命令计数和市场输入，创建稍后交给 Ledger 选择账户路由的查询对象。 */
    static RiskAccountQuery prepare(TradingCore owner, CoreMessage message) {
        String asset = null;
        int limit = 0;
        if (message.header().messageType() != CoreMessageType.RISK_STATE_QUERY) {
            var query = CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
            asset = AssetBalance.normalizeAsset(query.asset());
            limit = query.limit();
        }
        return new RiskAccountQuery(owner, owner.appliedCommandCount(), message.header().userId(), asset, limit,
                MarketInputs.capture(owner.accountBook(), owner.identities(), owner.accountBook().instrumentIdsForQuery()));
    }

    private RiskAccountQuery(TradingCore owner, long commandCount,
            long requestedUser, String asset, int limit, MarketInputs markets) {
        this.owner = owner; this.commandCount = commandCount; this.requestedUser = requestedUser;
        this.asset = asset; this.limit = limit; this.markets = markets;
        int count = owner.accountBook().topology().accountLaneCount();
        failures = new CoreResultCode[count];
        riskResults = new ArrayList<>(count); adlResults = new ArrayList<>(count);
        for (int lane = 0; lane < count; lane++) {
            riskResults.add(new ArrayList<>());
            adlResults.add(new ArrayList<>(Math.min(limit, 64)));
        }
        resolveRoutes();
    }

    private void resolveRoutes() {
        try {
            if (asset == null) {
                users = owner.positionUserIndex().riskQueryUserIds(requestedUser,
                        OperationalQueries.MAX_INDEX_SCAN);
            } else {
                var routes = owner.adlPositionIndex().positions(asset, OperationalQueries.MAX_INDEX_SCAN);
                users = new long[routes.size()]; positionKeys = new long[routes.size()];
                int index = 0;
                for (var route : routes) {
                    users[index] = route.userId();
                    positionKeys[index++] = com.surprising.aeron.service.core.identity.IdIndex.positionIdentityKey(
                            route.userId(), route.instrumentId(), route.positionSide());
                }
            }
            for (long user : users) laneMask |= owner.accountBook().topology().accountLaneMask(user);
            if (laneMask == 0) {
                complete(CoreQueryService.QueryResult.encoded(completedResponse()));
            }
        } catch (QueryTooLargeException failure) {
            failRoutes(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { failRoutes(CoreResultCode.INVALID_COMMAND); }
    }

    /** 保存带业务错误码的已编码响应，让 Owner 按普通查询结果消费。 */
    private void failRoutes(CoreResultCode code) {
        complete(CoreQueryService.QueryResult.encoded(rejected(code)));
    }

    /** 由 Owner 把查询投到所选账户 Lane。已直接回复则无需投递，邮箱暂不能接受时返回 false 供后续继续尝试。 */
    boolean dispatchAccounts() {
        if (accountsDispatched) return true;
        accountsDispatched = isDone() || owner.accountBook().dispatchAccountQuery(laneMask, this);
        return accountsDispatched;
    }

    /** 在所属 Lane 的合法只读范围执行查询，把可预期的参数或规模错误记入本 Lane 槽。原子标记完成，只有最后完成的 Lane 合并编码并通知 Owner，重复完成视为内部错误。 */
    @Override public void execute(AccountPartition lane) {
        try { owner.accountBook().readAccountLane(lane, this); }
        catch (QueryTooLargeException failure) {
            failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
        } catch (IllegalArgumentException failure) {
            failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
        }
        long bit = 1L << lane.laneId();
        long previous, complete;
        do {
            previous = completedLaneMask.get(); complete = previous | bit;
            if ((previous & bit) != 0) throw new IllegalStateException("risk query Lane completed twice");
        } while (!completedLaneMask.compareAndSet(previous, complete));
        if (complete != laneMask) return;
        CoreResponse response;
        try { response = completedResponse(); }
        catch (QueryTooLargeException failure) {
            response = rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        complete(CoreQueryService.QueryResult.encoded(response));
    }

    /** 在账户只读范围内过滤属于当前 Lane 的用户或持仓：风险查询重算用户快照，ADL 查询估值并保留有界候选。 */
    @Override public Void apply(AccountPartition lane) {
        var topology = owner.accountBook().topology();
        if (positionKeys == null) {
            var result = riskResults.get(lane.laneId());
            for (long user : users) {
                if (topology.accountLaneId(user) != lane.laneId()) continue;
                var rows = RiskQueries.userSnapshots(owner.accountBook(), owner.identities(), user, markets);
                if (result.size() + rows.size() > OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new QueryTooLargeException();
                result.addAll(rows);
            }
        } else {
            var result = adlResults.get(lane.laneId());
            for (int index = 0; index < users.length; index++) {
                if (topology.accountLaneId(users[index]) != lane.laneId()) continue;
                var candidate = RiskQueries.adlCandidate(owner.accountBook(), owner.identities(),
                        users[index], positionKeys[index], asset, markets);
                if (candidate != null) RiskQueries.addAdlCandidate(result, candidate, limit);
            }
        }
        return null;
    }

    /** 按 Lane 编号优先返回已有失败。否则风险快照先检查合并数量，再按用户、币对文本及持仓腿排序编码，ADL 则归并各 Lane 的有界候选。 */
    CoreResponse completedResponse() {
        for (CoreResultCode failure : failures) if (failure != null) return rejected(failure);
        byte[] encoded;
        if (positionKeys == null) {
            int count = 0;
            for (var rows : riskResults) count = Math.addExact(count, rows.size());
            if (count > OperationalQueries.MAX_QUERY_ENTITIES)
                throw new QueryTooLargeException();
            var result = new ArrayList<CoreRiskSnapshotView>(count);
            for (var rows : riskResults) result.addAll(rows);
            result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                    .thenComparing(CoreRiskSnapshotView::instrumentId)
                    // 用户及币对相同时用于比较持仓腿枚举顺序的风险快照项。
                    .thenComparingInt(value -> value.positionSide().ordinal()));
            encoded = CoreRiskQueryCodec.encode(result);
        } else encoded = CoreAdlQueryCodec.encodeCandidates(RiskQueries.mergeAdlCandidates(adlResults, limit));
        return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
    }

    private CoreResponse rejected(CoreResultCode code) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
    }
}
