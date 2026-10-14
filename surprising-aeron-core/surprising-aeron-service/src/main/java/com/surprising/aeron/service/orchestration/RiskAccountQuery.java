package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.AccountLaneState;
import com.surprising.aeron.service.state.model.AssetBalance;
import com.surprising.aeron.service.state.query.RuntimeOperationalQueryService;
import com.surprising.aeron.service.state.query.RuntimeRiskQueryService;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/** 单次风险/ADL查询的Lane交接：只保存路由、固定市场输入和有界响应DTO，消费后释放。 */
final class RiskAccountQuery implements SettlementLaneWorker.Command, Function<AccountLaneState, Void>,
        com.surprising.aeron.service.state.SystemLedgerLane.Entry {
    private final TradingCoreRuntime owner;
    final long id;
    private final long commandCount, requestedUser;
    private long laneMask;
    private long[] users, positionKeys;
    private volatile boolean routesReady;
    private boolean ledgerResponded;
    private final String asset;
    private final int limit;
    private final RuntimeRiskQueryService.MarketInputs markets;
    private final List<ArrayList<CoreRiskSnapshotView>> riskResults;
    private final List<ArrayList<CoreAdlCandidateView>> adlResults;
    private final CoreResultCode[] failures;
    private final AtomicLong completedLaneMask = new AtomicLong();

    /** Owner decodes the query; Ledger chooses bounded routes and Lanes read account values. */
    static RiskAccountQuery prepare(TradingCoreRuntime owner, CoreMessage message, long id) {
        String asset = null;
        int limit = 0;
        if (message.header().messageType() != CoreMessageType.RISK_STATE_QUERY) {
            var query = CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
            asset = AssetBalance.normalizeAsset(query.asset());
            limit = query.limit();
        }
        return new RiskAccountQuery(owner, id, owner.appliedCommandCount, message.header().userId(), asset, limit,
                RuntimeRiskQueryService.MarketInputs.capture(owner.runtimeState, owner.identities, owner.runtimeState.instrumentIdsForQuery()));
    }

    private RiskAccountQuery(TradingCoreRuntime owner, long id, long commandCount,
            long requestedUser, String asset, int limit, RuntimeRiskQueryService.MarketInputs markets) {
        this.owner = owner; this.id = id; this.commandCount = commandCount; this.requestedUser = requestedUser;
        this.asset = asset; this.limit = limit; this.markets = markets;
        int count = owner.runtimeState.topology().accountLaneCount();
        failures = new CoreResultCode[count];
        riskResults = new ArrayList<>(count); adlResults = new ArrayList<>(count);
        for (int lane = 0; lane < count; lane++) {
            riskResults.add(new ArrayList<>());
            adlResults.add(new ArrayList<>(Math.min(limit, 64)));
        }
    }

    @Override public void apply(com.surprising.aeron.service.state.TreasuryRuntime ignored) {
        try {
            if (asset == null) {
                var routes = owner.positionUserIndex.riskQueryRoutes(requestedUser,
                        RuntimeOperationalQueryService.MAX_INDEX_SCAN);
                users = routes.userIds();
            } else {
                var routes = owner.adlPositionIndex.positions(asset, RuntimeOperationalQueryService.MAX_INDEX_SCAN);
                users = new long[routes.size()]; positionKeys = new long[routes.size()];
                int index = 0;
                for (var route : routes) {
                    users[index] = route.userId();
                    positionKeys[index++] = com.surprising.aeron.service.state.RuntimeIdentityRegistry.positionIdentityKey(
                            route.userId(), route.instrumentId(), route.positionSide());
                }
            }
            for (long user : users) laneMask |= owner.runtimeState.topology().accountLaneMask(user);
            if (laneMask == 0) {
                owner.bookQueries.completedBookQueries.put(id, OrderBookQueryService.CompletedBookQuery.encoded(completedResponse()));
                ledgerResponded = true;
            }
        } catch (RuntimeOperationalQueryService.QueryTooLargeException failure) {
            failRoutes(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { failRoutes(CoreResultCode.INVALID_COMMAND); }
        var runtime = owner.runtimeState;
        routesReady = true;
        runtime.signalOwnerCompletion();
    }

    private void failRoutes(CoreResultCode code) {
        ledgerResponded = true;
        owner.bookQueries.completedBookQueries.put(id, OrderBookQueryService.CompletedBookQuery.encoded(rejected(code)));
    }

    /** Owner publishes routes after Ledger readiness; market inputs retain query-entry versions. */
    boolean dispatchAccounts() {
        if (!routesReady) return false;
        if (ledgerResponded) return true;
        return owner.runtimeState.dispatchAccountQuery(laneMask, this);
    }

    @Override public void execute(AccountLaneState lane) {
        try { owner.runtimeState.readAccountLane(lane, this); }
        catch (RuntimeOperationalQueryService.QueryTooLargeException failure) {
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
        catch (RuntimeOperationalQueryService.QueryTooLargeException failure) {
            response = rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
        } catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
        owner.bookQueries.completedBookQueries.put(id, OrderBookQueryService.CompletedBookQuery.encoded(response));
        owner.runtimeState.signalOwnerCompletion();
    }

    @Override public Void apply(AccountLaneState lane) {
        var topology = owner.runtimeState.topology();
        if (positionKeys == null) {
            var result = riskResults.get(lane.laneId());
            for (long user : users) {
                if (topology.accountLaneId(user) != lane.laneId()) continue;
                var rows = RuntimeRiskQueryService.userSnapshots(owner.runtimeState, owner.identities, user, markets);
                if (result.size() + rows.size() > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                    throw new RuntimeOperationalQueryService.QueryTooLargeException();
                result.addAll(rows);
            }
        } else {
            var result = adlResults.get(lane.laneId());
            for (int index = 0; index < users.length; index++) {
                if (topology.accountLaneId(users[index]) != lane.laneId()) continue;
                var candidate = RuntimeRiskQueryService.adlCandidate(owner.runtimeState, owner.identities,
                        users[index], positionKeys[index], asset, markets);
                if (candidate != null) RuntimeRiskQueryService.addAdlCandidate(result, candidate, limit);
            }
        }
        return null;
    }

    /** 最后完成的Lane只归并查询DTO，Owner直接消费已编码响应。空路由也使用相同协议。 */
    CoreResponse completedResponse() {
        for (CoreResultCode failure : failures) if (failure != null) return rejected(failure);
        byte[] encoded;
        if (positionKeys == null) {
            int count = 0;
            for (var rows : riskResults) count = Math.addExact(count, rows.size());
            if (count > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            var result = new ArrayList<CoreRiskSnapshotView>(count);
            for (var rows : riskResults) result.addAll(rows);
            result.sort(Comparator.comparingLong(CoreRiskSnapshotView::userId)
                    .thenComparing(CoreRiskSnapshotView::instrumentId)
                    .thenComparingInt(value -> value.positionSide().ordinal()));
            encoded = CoreRiskQueryCodec.encode(result);
        } else encoded = CoreAdlQueryCodec.encodeCandidates(RuntimeRiskQueryService.mergeAdlCandidates(adlResults, limit));
        return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
    }

    private CoreResponse rejected(CoreResultCode code) {
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
    }
}
