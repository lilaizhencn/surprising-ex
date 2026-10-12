package com.surprising.aeron.service.orchestration;

import static com.surprising.aeron.service.orchestration.TradingCoreRuntime.*;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.lane.SettlementLaneWorker;
import com.surprising.aeron.service.state.AccountLaneState;
import com.surprising.aeron.service.state.query.RuntimeStateQueryService;
import java.util.concurrent.atomic.AtomicLong;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.service.state.InsuranceAllocationPolicy;
import com.surprising.aeron.service.state.SystemLedgerLane;
import com.surprising.aeron.service.state.TreasuryRuntime;
import com.surprising.aeron.service.state.query.RuntimeOperationalQueryService;
import com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.service.matching.BookBootstrapSnapshot;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.NavigableMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/** 异步查询结果路由：由实际Lane、撮合器或账本读取并编码，不复制权威状态。 */
final class OrderBookQueryService {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    OrderBookQueryService(TradingCoreRuntime owner) { this.owner = owner; }

    /** 实际所有者完成的查询；Owner 消费后移除。 */
    final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();

    /** 异步盘口查询失败标记；owner 生成对应终态响应。 */
    final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();

    /** 外部查询 ID 到内部异步查询 ID 的关联。 */
    final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();

    /** 有界盘口分页会话，避免跨页混用不同快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 下一个盘口内部查询 ID，与交易命令序号分离。 */
    long nextAsyncQueryId = Long.MIN_VALUE;

    static boolean accountQuery(CoreMessageType type) {
        return type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY
                || type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                || type == CoreMessageType.CLIENT_ORDER_STATE_QUERY || type == CoreMessageType.USER_OPEN_ORDERS_QUERY;
    }

    /** 内部账户查询复用现有异步结果登记；Owner不进入账户读作用域。 */
    CoreResponse beginAccountQuery(CoreMessage message) {
        var type = message.header().messageType();
        long userId = message.header().userId();
        long orderId = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                ? TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()) : 0;
        long clientKey = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? owner.identities.findClientKeyValue(userId, CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe())) : 0;
        long[] openOrderIds = null;
        if (type == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            var pageQuery = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
            long before = pageQuery.beforeOrderId() == 0 ? Long.MAX_VALUE : pageQuery.beforeOrderId();
            var page = owner.activeOrderIndex.page(userId, pageQuery.instrumentId(), before, pageQuery.limit());
            if (page.orderIds().isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount,
                    CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(List.of())));
            openOrderIds = new long[page.orderIds().size()];
            for (int index = 0; index < openOrderIds.length; index++) openOrderIds[index] = page.orderIds().get(index);
        }
        boolean orderQuery = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY;
        if (!orderQuery && openOrderIds == null && userId <= 0) return missingAccountResponse(type, owner.appliedCommandCount);
        long laneMask;
        if (openOrderIds != null) {
            laneMask = 0;
            for (long selected : openOrderIds) {
                var route = owner.activeOrderIndex.activeOrderRoute(selected);
                if (route == null) throw new IllegalStateException("active order query route is missing");
                laneMask |= owner.runtimeState.topology().accountLaneMask(route.userId());
            }
        } else if (orderQuery) {
            var route = owner.activeOrderIndex.activeOrderRoute(orderId);
            // 只有orderId的内部协议也支持保留的终态订单；无活跃路由时由各Lane原位探测。
            int count = owner.runtimeState.topology().accountLaneCount();
            laneMask = route != null ? owner.runtimeState.topology().accountLaneMask(route.userId())
                    : count == Long.SIZE ? -1L : (1L << count) - 1;
        } else laneMask = owner.runtimeState.topology().accountLaneMask(userId);
        long id = nextAsyncQueryId++;
        var query = new AccountQuery(id, owner.appliedCommandCount, type, userId, orderId, clientKey, laneMask, openOrderIds);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState.dispatchAccountQuery(laneMask, query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    private static CoreResponse missingAccountResponse(CoreMessageType type, long commandCount) {
        if (type == CoreMessageType.USER_STATE_HASH_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY)
            return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.ENTITY_NOT_FOUND, commandCount);
    }

    /** 每请求只保留本次响应和完成位，结果消费/关闭后整体释放，无账户镜像。 */
    private final class AccountQuery implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountLaneState, CoreResponse> {
        private final long id, commandCount, userId, orderId, clientKey, laneMask;
        private final CoreMessageType type;
        private final CoreResponse[] responses;
        private final boolean[] found;
        private final long[] openOrderIds;
        private final com.surprising.aeron.protocol.CoreOrderStateView[] openOrders;
        private final AtomicLong completedLaneMask = new AtomicLong();

        private AccountQuery(long id, long commandCount, CoreMessageType type, long userId,
                             long orderId, long clientKey, long laneMask, long[] openOrderIds) {
            this.id = id; this.commandCount = commandCount; this.type = type;
            this.userId = userId; this.orderId = orderId; this.clientKey = clientKey; this.laneMask = laneMask;
            int count = owner.runtimeState.topology().accountLaneCount();
            responses = new CoreResponse[count]; found = new boolean[count];
            this.openOrderIds = openOrderIds;
            openOrders = openOrderIds == null ? null : new com.surprising.aeron.protocol.CoreOrderStateView[openOrderIds.length];
        }

        @Override public void execute(AccountLaneState lane) {
            CoreResponse response;
            try { response = owner.runtimeState.readAccountLane(lane, this); }
            catch (IllegalArgumentException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            responses[lane.laneId()] = response;
            long bit = 1L << lane.laneId();
            long previous, complete;
            do {
                previous = completedLaneMask.get(); complete = previous | bit;
                if ((previous & bit) != 0) throw new IllegalStateException("account query Lane completed twice");
            } while (!completedLaneMask.compareAndSet(previous, complete));
            if (complete != laneMask) return;
            CoreResponse completed;
            try { completed = completedResponse(); }
            catch (IllegalArgumentException invalid) {
                completed = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(completed));
            owner.runtimeState.signalOwnerCompletion();
        }

        @Override public CoreResponse apply(AccountLaneState lane) {
            if (openOrderIds != null) {
                for (int index = 0; index < openOrderIds.length; index++) {
                    var query = RuntimeStateQueryService.orderState(owner.runtimeState, owner.identities, openOrderIds[index]);
                    if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
                }
                return null;
            }
            if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
                var query = RuntimeStateQueryService.userState(owner.runtimeState, owner.identities, userId);
                if (query.tooLarge()) return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
                found[lane.laneId()] = query.found();
                if (type == CoreMessageType.USER_STATE_HASH_QUERY)
                    return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(query.stateHash()));
                return query.found() ? new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeUserState(query.view()))
                        : missingAccountResponse(type, commandCount);
            }
            Long clientOrderId = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY && clientKey != 0
                    ? owner.runtimeState.orderIdByClient(userId, clientKey) : null;
            long selectedOrder = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY ? clientOrderId == null ? 0 : clientOrderId : orderId;
            var query = selectedOrder == 0 ? null : RuntimeStateQueryService.orderState(owner.runtimeState, owner.identities, selectedOrder);
            found[lane.laneId()] = query != null && query.found();
            if (!found[lane.laneId()]) return missingAccountResponse(type, commandCount);
            byte[] encoded = type == CoreMessageType.ORDER_STATE_HASH_QUERY
                    ? CoreStateQueryCodec.encodeStateHash(query.stateHash()) : CoreStateQueryCodec.encodeOrderState(query.view());
            return new CoreResponse(ResponseStatus.OK, commandCount, encoded);
        }

        private CoreResponse completedResponse() {
            long pending = laneMask;
            while (pending != 0) {
                int laneId = Long.numberOfTrailingZeros(pending); pending &= pending - 1;
                var response = responses[laneId];
                if (response != null && (response.resultCode() == CoreResultCode.INVALID_COMMAND
                        || response.resultCode() == CoreResultCode.QUERY_RESPONSE_TOO_LARGE)) return response;
            }
            if (openOrders != null) {
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreOrderStateView>(openOrders.length);
                for (var order : openOrders) if (order != null) selected.add(order);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(selected)));
            }
            CoreResponse selected = null;
            long lanes = laneMask;
            while (lanes != 0) {
                int laneId = Long.numberOfTrailingZeros(lanes); lanes &= lanes - 1;
                if (!found[laneId]) continue;
                if (selected != null) throw new IllegalStateException("order identity is present in multiple Account Lanes");
                selected = responses[laneId];
            }
            return selected == null ? responses[Long.numberOfTrailingZeros(laneMask)] : selected;
        }
    }

    /** 财库查询复用已有异步查询ID及结果槽，Owner派发后立即返回。 */
    CoreResponse beginTreasuryQuery(CoreMessage message) { return beginLedgerQuery(message, null, null); }

    CoreResponse beginInsuranceQuery(CoreMessage message, CoreLiquidationWorkView work,
            InsuranceAllocationPolicy.AllocationInputs claims) {
        return beginLedgerQuery(message, work, claims);
    }

    private CoreResponse beginLedgerQuery(CoreMessage message, CoreLiquidationWorkView work,
            InsuranceAllocationPolicy.AllocationInputs claims) {
        long id = nextAsyncQueryId++;
        queryIds.put(message.header().commandId(), id);
        LedgerQuery query = new LedgerQuery(id, owner.appliedCommandCount, work, claims);
        try {
            if (!owner.runtimeState.dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    private final class LedgerQuery implements SystemLedgerLane.Entry {
        private final long id;
        private final long commandCount;
        private final CoreLiquidationWorkView insuranceWork;
        private final InsuranceAllocationPolicy.AllocationInputs claims;
        private LedgerQuery(long id, long commandCount, CoreLiquidationWorkView work,
                InsuranceAllocationPolicy.AllocationInputs claims) {
            this.id = id;
            this.commandCount = commandCount;
            this.insuranceWork = work;
            this.claims = claims;
        }
        @Override public void apply(TreasuryRuntime ledger) {
            CoreResponse response;
            try {
                byte[] encoded;
                if (insuranceWork == null) {
                    var assets = RuntimeOperationalQueryService.treasuryAssets(ledger, owner.identities);
                    encoded = CoreStateQueryCodec.encodeTreasuryState(assets);
                } else {
                    var allocations = InsuranceAllocationPolicy.allocations(ledger, claims);
                    var work = RuntimeLiquidationQueryService.insuranceRecommendations(insuranceWork, allocations);
                    encoded = CoreLiquidationWorkCodec.encodeWork(work);
                }
                response = new CoreResponse(ResponseStatus.OK, commandCount, encoded);
            } catch (RuntimeOperationalQueryService.QueryTooLargeException failure) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (IllegalArgumentException failure) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState.signalOwnerCompletion();
        }
    }

    CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadUnsafe().length == 0) {
            throw new IllegalArgumentException("single-instrumentId book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        long queryId = nextAsyncQueryId++;
        try {
            owner.matcherPipeline.readAtSubmissionFence(owner.matchingAdapter.matcherShardId(query.instrumentId()),
                    () -> owner.matchingAdapter.orderBookLevelsAsync(query.instrumentId(), query.depth()).join())
                    .whenComplete((levels, failure) -> {
                        if (failure != null) failedQueries.put(queryId, true);
                        else completedBookQueries.put(queryId, CompletedBookQuery.single(levels));
                    });
        } catch (RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount);
    }

    CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
            BookBootstrapSession session = bookBootstrapSessions.get(query.snapshotId());
            if (session == null || session.depth() != query.depth()) {
                return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
            }
            return bootstrapPageResponse(session, query);
        }
        long queryId = nextAsyncQueryId++;
        try {
            var snapshot = owner.matchingAdapter.topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline.readAtSubmissionFence(0,
                    () -> owner.matchingAdapter.orderBookBootstrapAsync(query.depth()).join())
                    : owner.matcherPipeline.readEachAsync(
                    shardId -> owner.matchingAdapter.orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter::mergeBookBootstrapShards);
            snapshot.whenComplete((value, failure) -> {
                if (failure != null) failedQueries.put(queryId, true);
                else completedBookQueries.put(queryId, CompletedBookQuery.bootstrap(
                        message.header().commandId().toString(), query, value));
            });
        } catch (RuntimeException failure) {
            failedQueries.put(queryId, true);
        }
        queryIds.put(message.header().commandId(), queryId);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(),
                owner.appliedCommandCount);
    }

    public long querySequence(UUID queryId) {
        return queryIds.getOrDefault(queryId, 0L);
    }

    public CoreResponse takeQueryResult(long queryId) {
        if (failedQueries.remove(queryId) != null) {
            queryIds.values().removeIf(value -> value == queryId);
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        CompletedBookQuery completed = completedBookQueries.remove(queryId);
        if (completed == null) return null;
        queryIds.values().removeIf(value -> value == queryId);
        if (completed.encodedResponse() != null) return completed.encodedResponse();
        long exportSequence = owner.commits.publication.publishedSequence();
        if (completed.bootstrapSnapshot() == null) {
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= TradingCoreRuntime.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    CoreResponse bootstrapPageResponse(BookBootstrapSession session, CoreOrderBookBootstrapQuery query) {
        if (!query.symbolCursor().isEmpty() && !session.symbols().containsKey(query.symbolCursor())) {
            return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
        }
        List<String> symbols = session.symbols().tailMap(query.symbolCursor(), false).keySet().stream()
                .limit(query.limit()).toList();
        int expectedLevels = 0;
        for (String instrumentId : symbols) {
            expectedLevels = Math.addExact(expectedLevels, session.symbols().get(instrumentId).size());
        }
        List<com.surprising.aeron.protocol.CoreBookLevelView> levels = new ArrayList<>(expectedLevels);
        for (String instrumentId : symbols) levels.addAll(session.symbols().get(instrumentId));
        boolean complete = symbols.isEmpty()
                || session.symbols().higherKey(symbols.getLast()) == null;
        String nextCursor = complete ? "" : symbols.getLast();
        CoreOrderBookBootstrapPage page = new CoreOrderBookBootstrapPage(session.snapshotId(),
                session.exportSequence(), nextCursor, complete, levels);
        byte[] encoded = CoreStateQueryCodec.encodeOrderBookBootstrapPage(page);
        return boundedBookResponse(levels.size(), encoded);
    }

    CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > TradingCoreRuntime.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCoreRuntime.MAX_BOOK_RESPONSE_BYTES) {
            return owner.rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount, encoded);
    }

    record CompletedBookQuery(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            BookBootstrapSnapshot bootstrapSnapshot,
            CoreResponse encodedResponse) {

        CompletedBookQuery(List<com.surprising.aeron.protocol.CoreBookLevelView> levels, String snapshotId,
                           CoreOrderBookBootstrapQuery bootstrapQuery, BookBootstrapSnapshot bootstrapSnapshot) {
            this(levels, snapshotId, bootstrapQuery, bootstrapSnapshot, null);
        }

        static CompletedBookQuery encoded(CoreResponse response) { return new CompletedBookQuery(List.of(), "", null, null, response); }

        static CompletedBookQuery single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new CompletedBookQuery(List.copyOf(levels), "", null, null);
        }

        static CompletedBookQuery bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                BookBootstrapSnapshot snapshot) {
            return new CompletedBookQuery(List.of(), snapshotId, query, snapshot);
        }
    }

    record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                BookBootstrapSnapshot snapshot) {
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> grouped = new TreeMap<>();
            for (String instrumentId : snapshot.symbols()) grouped.put(instrumentId, new ArrayList<>());
            for (com.surprising.aeron.protocol.CoreBookLevelView level : snapshot.levels()) {
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels = grouped.get(level.instrumentId());
                if (levels == null) throw new IllegalStateException("bootstrap level references unknown instrumentId");
                levels.add(level);
            }
            grouped.replaceAll((instrumentId, levels) -> List.copyOf(levels));
            return new BookBootstrapSession(snapshotId, exportSequence, depth,
                    Collections.unmodifiableNavigableMap(grouped));
        }
    }
}
