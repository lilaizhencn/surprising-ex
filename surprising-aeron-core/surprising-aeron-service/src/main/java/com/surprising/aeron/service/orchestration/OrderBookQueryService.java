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
public final class OrderBookQueryService {
    /** 唯一交易执行 owner；共享提交上下文，不复制账户或订单状态。 */
    final TradingCoreRuntime owner;

    OrderBookQueryService(TradingCoreRuntime owner) { this.owner = owner; }

    /** 实际所有者完成的查询；Owner 消费后移除。 */
    final Map<Long, CompletedBookQuery> completedBookQueries
            = new ConcurrentHashMap<>();

    /** 异步盘口查询失败标记；owner 生成对应终态响应。 */
    final Map<Long, Boolean> failedQueries = new ConcurrentHashMap<>();

    /** Owner-only pending query handoffs; entries disappear once their Account mail is published. */
    private final Map<Long, RiskAccountQuery> pendingRiskQueries = new java.util.HashMap<>();

    /** 外部查询 ID 到内部异步查询 ID 的关联。 */
    final Map<UUID, Long> queryIds = new ConcurrentHashMap<>();

    /** 有界盘口分页会话，避免跨页混用不同快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 下一个盘口内部查询 ID，与交易命令序号分离。 */
    long nextAsyncQueryId = Long.MIN_VALUE;

    static boolean accountQuery(CoreMessageType type) {
        return type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY
                || type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                || type == CoreMessageType.CLIENT_ORDER_STATE_QUERY || type == CoreMessageType.USER_OPEN_ORDERS_QUERY
                || type == CoreMessageType.ALGO_ORDER_QUERY;
    }

    static boolean riskAccountQuery(CoreMessageType type) {
        return type == CoreMessageType.RISK_STATE_QUERY || type == CoreMessageType.ADL_CANDIDATE_QUERY;
    }

    CoreResponse beginPendingTransferQuery(CoreMessage message, int limit) {
        var page = owner.runtimeState.pendingTransferRoutes(limit);
        if (page.ids().length == 0) {
            owner.runtimeState.completePendingTransferPage(page);
            return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount,
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(List.of()));
        }
        long id = nextAsyncQueryId++;
        var query = new AccountQuery(id, owner.appliedCommandCount, CoreMessageType.PENDING_TRANSFER_QUERY,
                0, 0, null, page.laneMask(), null, null);
        query.transferPage = new PendingTransferQueryPage(page.ids(), page.users(),
                new com.surprising.aeron.protocol.CorePendingTransferView[page.ids().length]);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState.dispatchAccountQuery(page.laneMask(), query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        owner.runtimeState.completePendingTransferPage(page);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    CoreResponse beginPreflightQuery(CoreMessage message, long timestamp) {
        var command = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
        long userId = message.header().userId();
        if (userId <= 0) throw new IllegalArgumentException("preflight user is required");
        owner.requireOrderIdentityAvailable(userId, command);
        if (owner.orderIdInUse(command.orderId())) throw new com.surprising.aeron.service.exception.CoreStateRejectedException(
                "DUPLICATE_ORDER_ID", "orderId already exists");
        var context = com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.queryContext(
                owner.runtimeState, owner.identities, userId, command.instrumentId(), timestamp);
        long id = nextAsyncQueryId++;
        var query = new PreflightQuery(id, owner.appliedCommandCount, userId, command, context);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!query.accountReady() && !owner.runtimeState.dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
            if (!owner.runtimeState.dispatchAccountQuery(owner.runtimeState.topology().accountLaneMask(userId), query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    /** 单Lane预检仅保留本次已准备输入；完成Lane编码，Owner消费后引用释放。 */
    private final class PreflightQuery implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountLaneState, CoreResponse>, SystemLedgerLane.Entry {
        private final long id, commandCount, userId;
        private long openInterest;
        private volatile boolean inputReady;
        private final com.surprising.aeron.protocol.PlaceOrderCommand command;
        private final com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.Context context;

        private PreflightQuery(long id, long commandCount, long userId, com.surprising.aeron.protocol.PlaceOrderCommand command,
                com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.Context context) {
            this.id = id; this.commandCount = commandCount; this.userId = userId;
            this.command = command; this.context = context;
            inputReady = !context.instrument().usesOpenInterestForUserLimit() || command.reduceOnly();
        }

        @Override public boolean accountReady() { return inputReady; }

        @Override public void apply(TreasuryRuntime ignored) {
            openInterest = owner.runtimeState.ledgerAdmissionOpenInterest(command.instrumentId());
            inputReady = true;
        }

        @Override public void execute(AccountLaneState lane) {
            CoreResponse response;
            try { response = owner.runtimeState.readAccountLane(lane, this); }
            catch (com.surprising.aeron.service.exception.CoreStateRejectedException failure) {
                response = rejected(CoreResultCode.fromRejectionCode(failure.code()));
            } catch (ArithmeticException failure) { response = rejected(CoreResultCode.ARITHMETIC_OVERFLOW); }
            catch (IllegalArgumentException failure) { response = rejected(CoreResultCode.INVALID_COMMAND); }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState.signalOwnerCompletion();
        }

        @Override public CoreResponse apply(AccountLaneState lane) {
            var order = com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver.resolve(context, userId, command);
            var identity = new com.surprising.aeron.service.state.admission.AdmissionIdentity(
                    owner.identities.findClientKeyValue(userId, command.clientOrderId()), context.symbolId(),
                    owner.identities.findPositionKeyValueInLane(lane, userId, context.instrument(), command.positionSide()),
                    context.settlementStatus());
            long reserved = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservationForQuery(
                    owner.runtimeState, lane, userId, order, openInterest, identity);
            return new CoreResponse(ResponseStatus.OK, commandCount, com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(
                    new com.surprising.aeron.protocol.CoreOrderPreflightView(order.reservationAsset(), reserved)));
        }

        private CoreResponse rejected(CoreResultCode code) {
            return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, code, commandCount);
        }
    }

    CoreResponse beginRiskAccountQuery(CoreMessage message) {
        long id = nextAsyncQueryId++;
        var query = RiskAccountQuery.prepare(owner, message, id);
        queryIds.put(message.header().commandId(), id);
        try {
            if (!owner.runtimeState.dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            queryIds.remove(message.header().commandId());
            throw failure;
        }
        pendingRiskQueries.put(id, query);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    /** 内部账户查询复用现有异步结果登记；Owner不进入账户读作用域。 */
    CoreResponse beginAccountQuery(CoreMessage message) {
        var type = message.header().messageType();
        if (type == CoreMessageType.ALGO_ORDER_QUERY) return beginAlgoAccountQuery(message);
        long userId = message.header().userId();
        long orderId = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                ? TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()) : 0;
        String clientName = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()) : null;
        if (type == CoreMessageType.CLIENT_ORDER_STATE_QUERY && userId <= 0)
            throw new IllegalArgumentException("userId must be positive");
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
        var query = new AccountQuery(id, owner.appliedCommandCount, type, userId, orderId, clientName, laneMask, openOrderIds, null);
        return dispatchAccountQuery(message, id, laneMask, query);
    }

    CoreResponse beginTriggerQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
        long before = requested.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : requested.beforeTriggerOrderId();
        Iterable<Long> source = requested.expiresBeforeEpochMillis() > 0
                ? owner.triggerOrderIndex.expired(requested.expiresBeforeEpochMillis(), requested.limit())
                : requested.instrumentId().isEmpty()
                ? requested.status() != null ? owner.triggerOrderIndex.ids(requested.status())
                : message.header().userId() == 0 ? owner.triggerOrderIndex.ids() : owner.triggerOrderIndex.ids(message.header().userId())
                : requested.status() == null ? owner.triggerOrderIndex.ids(requested.instrumentId())
                : owner.triggerOrderIndex.ids(requested.instrumentId(), requested.status());
        var selected = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        var users = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        long laneMask = 0;
        int scanned = 0;
        for (long id : source) {
            if (++scanned > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
                throw new RuntimeOperationalQueryService.QueryTooLargeException();
            var route = owner.triggerOrderIndex.route(id);
            if (route == null || id >= before || requested.triggerOrderId() != 0 && requested.triggerOrderId() != id
                    || message.header().userId() != 0 && message.header().userId() != route.userId()
                    || !requested.instrumentId().isEmpty() && !requested.instrumentId().equals(route.instrumentId())
                    || requested.status() != null && requested.status() != route.status()
                    || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY && !route.status().open()) continue;
            selected.add(id); users.add(route.userId());
            laneMask |= owner.runtimeState.topology().accountLaneMask(route.userId());
            if (selected.size() == Math.min(requested.limit(), RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)) break;
        }
        if (selected.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount,
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of()));
        long queryId = nextAsyncQueryId++;
        var query = new AccountQuery(queryId, owner.appliedCommandCount, message.header().messageType(),
                0, 0, null, laneMask, null, null);
        query.triggerPage = new TriggerQueryPage(selected.toArray(), users.toArray(),
                new com.surprising.aeron.protocol.CoreTriggerOrderStateView[selected.size()]);
        return dispatchAccountQuery(message, queryId, laneMask, query);
    }

    private CoreResponse beginAlgoAccountQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
        var ids = requested.algoOrderId() == 0
                ? owner.algoOrderIndex.query(requested.userId(), requested.instrumentId(), requested.dueAtEpochMillis(),
                        requested.limit())
                : owner.algoOrderIndex.value(requested.algoOrderId()) == null ? List.<Long>of() : List.of(requested.algoOrderId());
        if (ids.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount,
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(List.of()));
        long[] selectedIds = new long[ids.size()], users = new long[ids.size()];
        long laneMask = 0;
        for (int index = 0; index < ids.size(); index++) {
            long selected = ids.get(index);
            var route = owner.algoOrderIndex.value(selected);
            if (route == null) throw new IllegalStateException("algo query route is missing");
            selectedIds[index] = selected; users[index] = route.userId();
            laneMask |= owner.runtimeState.topology().accountLaneMask(route.userId());
        }
        long id = nextAsyncQueryId++;
        var page = new AlgoQueryPage(selectedIds, users,
                new com.surprising.aeron.protocol.CoreAlgoOrderView[selectedIds.length]);
        var query = new AccountQuery(id, owner.appliedCommandCount, CoreMessageType.ALGO_ORDER_QUERY,
                0, 0, null, laneMask, null, page);
        return dispatchAccountQuery(message, id, laneMask, query);
    }

    private CoreResponse dispatchAccountQuery(CoreMessage message, long id, long laneMask, AccountQuery query) {
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

    /** 本次算法查询的固定路由与响应槽；各Lane只写自己的下标，完成位发布后归并。 */
    private record TriggerQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreTriggerOrderStateView[] views) { }

    private record AlgoQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CoreAlgoOrderView[] views) { }

    private record PendingTransferQueryPage(long[] ids, long[] users, com.surprising.aeron.protocol.CorePendingTransferView[] views) { }

    private static CoreResponse missingAccountResponse(CoreMessageType type, long commandCount) {
        if (type == CoreMessageType.USER_STATE_HASH_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY)
            return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.ENTITY_NOT_FOUND, commandCount);
    }

    /** 每请求只保留本次响应和完成位，结果消费/关闭后整体释放，无账户镜像。 */
    private final class AccountQuery implements SettlementLaneWorker.Command,
            java.util.function.Function<AccountLaneState, CoreResponse> {
        private final long id, commandCount, userId, orderId, laneMask;
        private final String clientName;
        private final CoreMessageType type;
        private final CoreResponse[] responses;
        private final boolean[] found;
        private final long[] openOrderIds;
        private final com.surprising.aeron.protocol.CoreOrderStateView[] openOrders;
        private final AlgoQueryPage algoPage;
        private TriggerQueryPage triggerPage;
        private PendingTransferQueryPage transferPage;
        private final AtomicLong completedLaneMask = new AtomicLong();

        private AccountQuery(long id, long commandCount, CoreMessageType type, long userId,
                             long orderId, String clientName, long laneMask, long[] openOrderIds, AlgoQueryPage algoPage) {
            this.id = id; this.commandCount = commandCount; this.type = type;
            this.userId = userId; this.orderId = orderId; this.clientName = clientName; this.laneMask = laneMask;
            int count = owner.runtimeState.topology().accountLaneCount();
            responses = new CoreResponse[count]; found = new boolean[count];
            this.openOrderIds = openOrderIds;
            this.algoPage = algoPage;
            openOrders = openOrderIds == null ? null : new com.surprising.aeron.protocol.CoreOrderStateView[openOrderIds.length];
        }

        @Override public void execute(AccountLaneState lane) {
            CoreResponse response;
            try { response = owner.runtimeState.readAccountLane(lane, this); }
            catch (RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            }
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
            if (transferPage != null) {
                for (int index = 0; index < transferPage.ids.length; index++) {
                    if (owner.runtimeState.topology().accountLaneId(transferPage.users[index]) != lane.laneId()) continue;
                    var value = owner.runtimeState.pendingTransfer(transferPage.ids[index]);
                    if (value == null || value.userId() != transferPage.users[index])
                        throw new IllegalArgumentException("pending transfer routing differs from Lane identity");
                    transferPage.views[index] = new com.surprising.aeron.protocol.CorePendingTransferView(value.userId(), value.command());
                }
                return null;
            }
            if (triggerPage != null) {
                for (int index = 0; index < triggerPage.ids.length; index++) {
                    if (owner.runtimeState.topology().accountLaneId(triggerPage.users[index]) != lane.laneId()) continue;
                    var value = owner.runtimeState.triggerOrder(triggerPage.ids[index]);
                    if (value != null) triggerPage.views[index] = value.view();
                }
                return null;
            }
            if (algoPage != null) {
                for (int index = 0; index < algoPage.ids.length; index++) {
                    if (owner.runtimeState.topology().accountLaneId(algoPage.users[index]) != lane.laneId()) continue;
                    var views = RuntimeOperationalQueryService.algoOrders(owner.runtimeState, List.of(algoPage.ids[index]));
                    if (!views.isEmpty()) algoPage.views[index] = views.getFirst();
                }
                return null;
            }
            if (openOrderIds != null) {
                for (int index = 0; index < openOrderIds.length; index++) {
                    var query = RuntimeStateQueryService.orderState(owner.runtimeState, lane, owner.identities, openOrderIds[index]);
                    if (query.found() && "OPEN".equals(query.view().status())) openOrders[index] = query.view();
                }
                return null;
            }
            if (type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY) {
                var query = RuntimeStateQueryService.userState(owner.runtimeState, lane, owner.identities, userId);
                if (query.tooLarge()) return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
                found[lane.laneId()] = query.found();
                if (type == CoreMessageType.USER_STATE_HASH_QUERY)
                    return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(query.stateHash()));
                return query.found() ? new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeUserState(query.view()))
                        : missingAccountResponse(type, commandCount);
            }
            var query = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                    ? RuntimeStateQueryService.clientOrderState(owner.runtimeState, lane, owner.identities, userId, clientName)
                    : orderId == 0 ? null : RuntimeStateQueryService.orderState(owner.runtimeState, lane, owner.identities, orderId);
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
            if (transferPage != null) return new CoreResponse(ResponseStatus.OK, commandCount,
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(java.util.Arrays.asList(transferPage.views)));
            if (triggerPage != null) {
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreTriggerOrderStateView>(triggerPage.views.length);
                for (var view : triggerPage.views) if (view != null) selected.add(view);
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(selected));
            }
            if (algoPage != null) {
                var selected = new ArrayList<com.surprising.aeron.protocol.CoreAlgoOrderView>(algoPage.views.length);
                int children = 0;
                for (var view : algoPage.views) if (view != null) {
                    children = Math.addExact(children, view.childOrderIds().size());
                    if (children > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
                        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                                CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
                    selected.add(view);
                }
                return new CoreResponse(ResponseStatus.OK, commandCount,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(selected));
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
    CoreResponse beginTreasuryQuery(CoreMessage message) { return beginLedgerQuery(message); }

    private LiquidationWorkQuery deferredLiquidationWorkQuery;

    CoreResponse beginLiquidationWorkQuery(CoreMessage message, CoreLiquidationWorkView.Query requested) {
        if (deferredLiquidationWorkQuery != null) return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        var candidates = requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? owner.liquidationIndex.activeIds()
                : owner.liquidationIndex.activeIds().tailSet(requested.afterLiquidationId(), false);
        if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                && candidates.size() > RuntimeOperationalQueryService.MAX_INDEX_SCAN)
            throw new RuntimeOperationalQueryService.QueryTooLargeException();
        long queryId = nextAsyncQueryId++;
        var query = new LiquidationWorkQuery(queryId, owner.appliedCommandCount, requested, candidates);
        queryIds.put(message.header().commandId(), queryId);
        if (query.laneMask != 0 && !owner.runtimeState.dispatchAccountQuery(query.laneMask, query)) {
            queryIds.remove(message.header().commandId());
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        if (!owner.runtimeState.dispatchLedgerQuery(query)) deferredLiquidationWorkQuery = query;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    /** Lane reads execution/ADL facts; Ledger values insurance claims and encodes the bounded page. */
    private final class LiquidationWorkQuery implements SettlementLaneWorker.Command, SystemLedgerLane.Entry {
        private final long id, commandCount, laneMask;
        private final CoreLiquidationWorkView.Query requested;
        private final long[] ids;
        private final int[] lanes, assetIds;
        private final com.surprising.aeron.service.state.instrument.CoreInstrument[] instruments;
        private final CoreLiquidationWorkView.Resolution[] receipts;
        private final com.surprising.aeron.protocol.CoreLiquidationActionView[] actions;
        private final com.surprising.aeron.service.state.market.MarkPriceRuntime[] marks;
        private final com.surprising.aeron.protocol.CoreRiskScanContinuation continuation;
        private final boolean sourceComplete;
        private final CoreResultCode[] failures;
        private final InsuranceAllocationPolicy.AllocationInputs[] claims;
        private final java.util.concurrent.atomic.AtomicLong completed = new java.util.concurrent.atomic.AtomicLong();
        LiquidationWorkQuery(long id, long commandCount, CoreLiquidationWorkView.Query requested, java.util.NavigableSet<Long> candidates) {
            this.id = id; this.commandCount = commandCount; this.requested = requested;
            int count = Math.min(candidates.size(), RuntimeOperationalQueryService.MAX_INDEX_SCAN);
            sourceComplete = count == candidates.size();
            continuation = RuntimeLiquidationQueryService.continuation(owner.runtimeState, owner.identities, requested.purpose());
            ids = new long[count]; lanes = new int[ids.length]; assetIds = new int[ids.length];
            instruments = new com.surprising.aeron.service.state.instrument.CoreInstrument[ids.length];
            receipts = new CoreLiquidationWorkView.Resolution[ids.length];
            actions = new com.surprising.aeron.protocol.CoreLiquidationActionView[ids.length];
            marks = new com.surprising.aeron.service.state.market.MarkPriceRuntime[ids.length];
            failures = new CoreResultCode[owner.runtimeState.topology().accountLaneCount()];
            claims = new InsuranceAllocationPolicy.AllocationInputs[owner.runtimeState.topology().accountLaneCount()];
            long mask = 0; int index = 0;
            for (long liquidationId : candidates) {
                if (index == count) break;
                var route = owner.liquidationIndex.route(liquidationId);
                if (route == null) throw new IllegalStateException("liquidation query route is missing");
                instruments[index] = owner.runtimeState.instrument(route.instrumentId());
                assetIds[index] = owner.identities.assetId(instruments[index].settleAsset());
                marks[index] = owner.runtimeState.markPrice(owner.identities.findSymbolId(route.instrumentId()));
                ids[index] = liquidationId; lanes[index] = owner.runtimeState.topology().accountLaneId(route.userId());
                mask |= 1L << lanes[index++];
            }
            laneMask = mask;
        }
        @Override public void execute(AccountLaneState lane) {
            try { owner.runtimeState.readAccountLane(lane, owned -> {
                for (int index = 0; index < ids.length; index++) {
                    if (lanes[index] != owned.laneId()) continue;
                    var value = owner.runtimeState.liquidation(ids[index]);
                    if (value == null) continue;
                    var purpose = requested.purpose();
                    if (purpose == CoreLiquidationWorkView.Purpose.EXECUTION) {
                        var mark = marks[index];
                        if ((value.status() != com.surprising.aeron.service.state.model.CoreLiquidationState.Status.PLANNED
                                && value.status() != com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ORDERED)
                                || mark == null || mark.priceSequence() != value.triggerPriceSequence()) continue;
                        actions[index] = new com.surprising.aeron.protocol.CoreLiquidationActionView(value.liquidationId(),
                                value.userId(), instruments[index].instrumentId(), value.marginMode(), value.positionSide(),
                                value.triggerPriceSequence(), value.signedQuantitySteps(), value.closeQuantitySteps(),
                                mark.markPriceTicks(), value.status().name(),
                                value.status() == com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ORDERED
                                        ? value.nextCancelOrderId() : 0);
                        continue;
                    }
                    var required = purpose == CoreLiquidationWorkView.Purpose.INSURANCE
                            ? com.surprising.aeron.service.state.model.CoreLiquidationState.Status.INSURANCE_REQUIRED
                            : com.surprising.aeron.service.state.model.CoreLiquidationState.Status.ADL_REQUIRED;
                    if (value.status() != required) continue;
                    var instrument = instruments[index];
                    if (instrument == null || instrument != value.instrument() || instrument.contractType().productLine() != owner.productLine) continue;
                    receipts[index] = new CoreLiquidationWorkView.Resolution(value.liquidationId(), value.userId(),
                            instrument.instrumentId(), instrument.settleAsset(), value.marginMode(), value.positionSide(),
                            value.triggerPriceSequence(), value.signedQuantitySteps(), value.deficitUnits(), 0, purpose);
                }
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE)
                    claims[owned.laneId()] = InsuranceAllocationPolicy.prepareLaneAllocations(owner.runtimeState, ids, assetIds, instruments);
                return null;
            }); }
            catch (RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                failures[lane.laneId()] = CoreResultCode.QUERY_RESPONSE_TOO_LARGE;
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                failures[lane.laneId()] = CoreResultCode.INVALID_COMMAND;
            }
            completed.getAndUpdate(mask -> mask | (1L << lane.laneId()));
            owner.runtimeState.signalOwnerCompletion();
        }
        @Override public boolean ready() { return completed.get() == laneMask; }
        @Override public void apply(TreasuryRuntime ledger) {
            CoreResponse response;
            try {
                for (CoreResultCode failure : failures) {
                    if (failure == null) continue;
                    completedBookQueries.put(id, CompletedBookQuery.encoded(new CoreResponse(
                            ResponseStatus.REJECTED, ResponseStatus.REJECTED, failure, commandCount)));
                    owner.runtimeState.signalOwnerCompletion();
                    return;
                }
                var result = RuntimeLiquidationQueryService.workPage(owner.productLine, requested, ids, actions,
                        receipts, continuation, sourceComplete);
                if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE) {
                    var allocations = InsuranceAllocationPolicy.allocations(ledger, InsuranceAllocationPolicy.mergeLaneClaims(claims));
                    result = RuntimeLiquidationQueryService.insuranceRecommendations(result, allocations);
                }
                response = new CoreResponse(ResponseStatus.OK, commandCount, CoreLiquidationWorkCodec.encodeWork(result));
            } catch (RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
            } catch (IllegalArgumentException | ArithmeticException invalid) {
                response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                        CoreResultCode.INVALID_COMMAND, commandCount);
            }
            completedBookQueries.put(id, CompletedBookQuery.encoded(response));
            owner.runtimeState.signalOwnerCompletion();
        }
    }

    CoreResponse beginPositionIndexQuery(CoreMessage message,
            com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        long id = nextAsyncQueryId++;
        queryIds.put(message.header().commandId(), id);
        var query = new LedgerQuery(id, owner.appliedCommandCount, message.header().messageType(), page, maintenance);
        try {
            if (!owner.runtimeState.dispatchLedgerQuery(query)) {
                queryIds.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) { queryIds.remove(message.header().commandId()); throw failure; }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, TradingCoreRuntime.matchingPendingCode(), owner.appliedCommandCount);
    }

    private CoreResponse beginLedgerQuery(CoreMessage message) {
        long id = nextAsyncQueryId++;
        queryIds.put(message.header().commandId(), id);
        LedgerQuery query = new LedgerQuery(id, owner.appliedCommandCount);
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
        private final CoreMessageType type;
        private final com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page;
        private final com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance;
        private LedgerQuery(long id, long commandCount) {
            this(id, commandCount, CoreMessageType.TREASURY_STATE_QUERY, null, null);
        }
        private LedgerQuery(long id, long commandCount, CoreMessageType type,
                com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page,
                com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
            this.id = id; this.commandCount = commandCount; this.type = type;
            this.page = page; this.maintenance = maintenance;
        }
        @Override public void apply(TreasuryRuntime ledger) {
            CoreResponse response;
            try {
                byte[] encoded;
                if (type == CoreMessageType.OPEN_INTEREST_QUERY) {
                    var totals = owner.openInterestIndex.totals();
                    if (totals.size() > RuntimeOperationalQueryService.MAX_QUERY_ENTITIES)
                        throw new RuntimeOperationalQueryService.QueryTooLargeException();
                    var views = new ArrayList<com.surprising.aeron.protocol.CoreOpenInterestView>(totals.size());
                    for (var entry : totals.entrySet()) {
                        views.add(new com.surprising.aeron.protocol.CoreOpenInterestView(
                                entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()));
                    }
                    encoded = com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views);
                } else if (type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
                    var users = new ArrayList<Long>(page.limit());
                    boolean more = false;
                    long next = owner.positionUserIndex.higherUserId(page.instrumentId(), page.afterUserId());
                    while (next != 0) {
                        if (users.size() == page.limit()) { more = true; break; }
                        users.add(next);
                        next = owner.positionUserIndex.higherUserId(page.instrumentId(), next);
                    }
                    encoded = com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                            new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(maintenance, users, more));
                } else encoded = CoreStateQueryCodec.encodeTreasuryState(
                        RuntimeOperationalQueryService.treasuryAssets(ledger, owner.identities));
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

    /** Called after the account and financial readers have stopped. */
    void clearPendingQueries() {
        completedBookQueries.clear();
        failedQueries.clear();
        queryIds.clear();
        deferredLiquidationWorkQuery = null;
        pendingRiskQueries.clear();
    }

    public CoreResponse takeQueryResult(long queryId) {
        var riskQuery = pendingRiskQueries.get(queryId);
        if (riskQuery != null && riskQuery.dispatchAccounts()) pendingRiskQueries.remove(queryId);
        if (deferredLiquidationWorkQuery != null && deferredLiquidationWorkQuery.id == queryId
                && owner.runtimeState.dispatchLedgerQuery(deferredLiquidationWorkQuery)) deferredLiquidationWorkQuery = null;
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
        var tailSymbols = session.symbols().tailMap(query.symbolCursor(), false).keySet();
        List<String> symbols = new ArrayList<>(Math.min(query.limit(), tailSymbols.size()));
        for (String symbol : tailSymbols) {
            symbols.add(symbol);
            if (symbols.size() >= query.limit()) break;
        }
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
