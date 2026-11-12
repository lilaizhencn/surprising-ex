package com.surprising.aeron.service.query;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.core.*;
import com.surprising.aeron.service.order.*;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapPage;
import com.surprising.aeron.protocol.CoreOrderBookBootstrapQuery;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.service.core.TradingCore;
import com.surprising.aeron.service.matcher.OrderBookDepthSnapshot;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.NavigableMap;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import lombok.RequiredArgsConstructor;
import com.surprising.aeron.service.result.CommandResultLog;

/** 核心内部查询的异步编排入口。Owner 解析条件并登记编号，账户 Lane、Matcher 或系统账本在各自所有权内读取。结果回到这里供 Owner 消费，不能为查询复制一份在线权威账户。参数 owner：所属交易运行时，提供查询投递、路由、身份和提交水位入口。 */
@RequiredArgsConstructor
public final class CoreQueryService {
    /** 提供业务路由、线程投递和提交水位的所属运行时，查询仍遵守实际状态所有权。 */
    final TradingCore owner;

    /** 只有 Owner 访问关联表。工作线程只完成请求自己的 Future。 */
    private final Map<UUID, CompletableFuture<QueryResult>> pendingQueries = new java.util.HashMap<>();

    /** Owner 持有的有界盘口初始化会话，按插入顺序淘汰最早会话，各页共用固定快照。 */
    final LinkedHashMap<String, BookBootstrapSession> bookBootstrapSessions = new LinkedHashMap<>();

    /** 判断协议类型是否属于普通账户、订单、挂单或算法父单查询，用于选择账户 Lane 读取入口。 */
    public static boolean accountQuery(CoreMessageType type) {
        return type == CoreMessageType.USER_STATE_QUERY || type == CoreMessageType.USER_STATE_HASH_QUERY
                || type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                || type == CoreMessageType.CLIENT_ORDER_STATE_QUERY || type == CoreMessageType.USER_OPEN_ORDERS_QUERY
                || type == CoreMessageType.ALGO_ORDER_QUERY;
    }

    /** 识别风险快照与 ADL 候选两类查询，交给需要先经账本选路由的风险查询任务。 */
    public static boolean riskAccountQuery(CoreMessageType type) {
        return type == CoreMessageType.RISK_STATE_QUERY || type == CoreMessageType.ADL_CANDIDATE_QUERY;
    }

    /** 查询入口：按协议类型选择 Owner 元数据、账户 Lane、账本或盘口读取，映射各分支明确捕获的业务错误。 */
    public CoreResponse routeQuery(CoreMessage message, long clusterTimestamp) {
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && accountQuery(message.header().messageType())) {
            try { return beginAccountQuery(message); }
            catch (QueryTooLargeException tooLarge) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException invalid) { return owner.rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(),
                    com.surprising.aeron.protocol.CoreStateQueryCodec.encodeStateHash(owner.stateHash()));
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadLength() != 0) return owner.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(), owner.encodeLaneMetrics());
        }
        if (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                var instrument = owner.accountBook().instrument(query.instrumentId());
                if (instrument == null) return owner.rejected(CoreResultCode.ENTITY_NOT_FOUND);
                return queryLedger(message.header().messageType(), query, instrument.maintenance());
            } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                java.util.UUID commandId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                CommandResultLog.StoredResult result = owner.commandResultLog().get(commandId);
                if (result == null) {
                    return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.REJECTED, com.surprising.aeron.protocol.ResponseStatus.REJECTED,
                            CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION, owner.appliedCommandCount(),
                            TradingCore.EMPTY_RESPONSE_DATA);
                }
                owner.responseSlotPool().retain(result.responseDataUnsafe());
                return CoreResponse.owned(com.surprising.aeron.protocol.ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(),
                        result.responseDataUnsafe(), result.responseDataOffsetUnsafe(), result.responseDataLength());
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && (message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try { return beginTriggerQuery(message);
            } catch (QueryTooLargeException exception) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.BOOK_STATE_QUERY) {
            try {
                return beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return beginBookBootstrapQuery(message);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                return queryLedger(CoreMessageType.TREASURY_STATE_QUERY, null, null);
            } catch (QueryTooLargeException exception) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                var view = OperationalQueries
                        .fundingProgress(owner.accountBook(), owner.identities(), instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(),
                        com.surprising.aeron.protocol.CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String instrumentId = com.surprising.aeron.protocol.CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                var view = OperationalQueries
                        .settlementProgress(owner.accountBook(), owner.identities(), instrumentId);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(),
                        com.surprising.aeron.protocol.CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && riskAccountQuery(message.header().messageType())) {
            try { return beginRiskAccountQuery(message); }
            catch (QueryTooLargeException failure) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException failure) { return owner.rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadLength() != 0) return owner.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(),
                    com.surprising.aeron.protocol.CoreRiskScanControlCodec.encodeView(owner.accountBook().riskScanControl()));
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.OPEN_INTEREST_QUERY) {
            return queryLedger(message.header().messageType(), null, null);
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                var keys = owner.bizIndex().timers().query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), owner.accountBook()::cancelAllAfterTimer);
                var values = OperationalQueries.cancelAllAfter(
                        owner.accountBook(), keys);
                return new CoreResponse(com.surprising.aeron.protocol.ResponseStatus.OK, owner.appliedCommandCount(),
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (QueryTooLargeException exception) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payloadUnsafe());
                if (query.productLine() != owner.productLine()) {
                    return owner.rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                return beginLiquidationWorkQuery(message, query);
            } catch (QueryTooLargeException exception) {
                return owner.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                return beginPreflightQuery(message, clusterTimestamp);
            } catch (com.surprising.aeron.exception.CoreStateRejectedException exception) {
                return owner.rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (ArithmeticException exception) {
                return owner.rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == com.surprising.aeron.protocol.WireMessageKind.QUERY
                && message.header().messageType() == com.surprising.aeron.protocol.CoreMessageType.PENDING_TRANSFER_QUERY) {
            try {
                int limit = com.surprising.aeron.protocol.CorePendingTransferCodec.decodeQuery(
                        message.payloadUnsafe());
                return beginPendingTransferQuery(message, limit);
            } catch (IllegalArgumentException exception) {
                return owner.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        return null;
    }

    /** 从 Owner 的待完成划转路由取一页，空页直接返回。 */
    public CoreResponse beginPendingTransferQuery(CoreMessage message, int limit) {
        var page = owner.accountBook().pendingTransferRoutes(limit);
        if (page.ids().length == 0) {
            owner.accountBook().completePendingTransferPage(page);
            return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    com.surprising.aeron.protocol.CorePendingTransferCodec.encode(List.of()));
        }
        var query = new AccountQuery(owner, owner.appliedCommandCount(), CoreMessageType.PENDING_TRANSFER_QUERY,
                0, 0, null, page.laneMask(), null, null);
        query.transferPage = new PendingTransferQueryPage(page.ids(), page.users(),
                new com.surprising.aeron.protocol.TransferFundsCommand[page.ids().length]);
        registerQuery(message, query);
        try {
            if (!owner.accountBook().dispatchAccountQuery(page.laneMask(), query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        owner.accountBook().completePendingTransferPage(page);
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 解码下单意图并检查用户和订单身份，固定币对决策输入。 */
    public CoreResponse beginPreflightQuery(CoreMessage message, long timestamp) {
        var command = TradingCommandCodec.decodePlaceOrderInto(message.payloadUnsafe(), 0, message.payloadLength(), null);
        long userId = message.header().userId();
        if (userId <= 0) throw new IllegalArgumentException("preflight user is required");
        owner.requireOrderIdentityAvailable(userId, command);
        if (owner.orderIdInUse(command.orderId())) throw new com.surprising.aeron.exception.CoreStateRejectedException(
                "DUPLICATE_ORDER_ID", "orderId already exists");
        var context = com.surprising.aeron.service.order.OrderCheckService.queryContext(
                owner.accountBook(), owner.identities(), command.instrumentId(), timestamp);
        var query = new PreflightQuery(owner, owner.appliedCommandCount(), userId, command, context);
        registerQuery(message, query);
        try {
            if (!owner.accountBook().dispatchAccountQuery(owner.accountBook().topology().accountLaneMask(userId), query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 创建风险查询对象，登记外部命令与请求完成状态，并投递到相应 Lane。 */
    public CoreResponse beginRiskAccountQuery(CoreMessage message) {
        var query = RiskAccountQuery.prepare(owner, message);
        registerQuery(message, query);
        query.dispatchAccounts();
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    /** 按查询类型解析用户、订单或客户端条件。 */
    public CoreResponse beginAccountQuery(CoreMessage message) {
        var type = message.header().messageType();
        if (type == CoreMessageType.ALGO_ORDER_QUERY) return beginAlgoAccountQuery(message);
        long userId = message.header().userId();
        long orderId = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY
                ? TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()) : 0;
        String clientName = type == CoreMessageType.CLIENT_ORDER_STATE_QUERY
                ? CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()) : null;
        if (type == CoreMessageType.CLIENT_ORDER_STATE_QUERY && userId <= 0)
            throw new IllegalArgumentException("userId must be positive");
        com.surprising.aeron.support.collection.ImmutableLongArrayList openOrderIds = null;
        if (type == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            var pageQuery = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
            long before = pageQuery.beforeOrderId() == 0 ? Long.MAX_VALUE : pageQuery.beforeOrderId();
            var page = owner.activeOrderIndex().page(userId, pageQuery.instrumentId(), before, pageQuery.limit());
            if (page.orderIds().isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                    CoreStateQueryCodec.encodeOpenOrders(new com.surprising.aeron.protocol.CoreOpenOrdersView(List.of())));
            openOrderIds = page.orderIds();
        }
        boolean orderQuery = type == CoreMessageType.ORDER_STATE_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY;
        if (!orderQuery && openOrderIds == null && userId <= 0) return missingAccountResponse(type, owner.appliedCommandCount());
        long laneMask;
        if (openOrderIds != null) {
            laneMask = 0;
            for (int index = 0; index < openOrderIds.size(); index++) {
                long selected = openOrderIds.valueAt(index);
                var route = owner.activeOrderIndex().activeOrderRoute(selected);
                if (route == null) throw new IllegalStateException("active order query route is missing");
                laneMask |= owner.accountBook().topology().accountLaneMask(route.userId());
            }
        } else if (orderQuery) {
            var route = owner.activeOrderIndex().activeOrderRoute(orderId);
            int count = owner.accountBook().topology().accountLaneCount();
            laneMask = route != null ? owner.accountBook().topology().accountLaneMask(route.userId())
                    : count == Long.SIZE ? -1L : (1L << count) - 1;
        } else laneMask = owner.accountBook().topology().accountLaneMask(userId);
        var query = new AccountQuery(owner, owner.appliedCommandCount(), type, userId, orderId, clientName, laneMask, openOrderIds, null);
        return dispatchAccountQuery(message, laneMask, query);
    }

    /** 按过期时间、状态、用户或币对选择触发索引来源，再施加精确编号、游标及活动状态筛选。 */
    public CoreResponse beginTriggerQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
        int requestedInstrument = requested.instrumentId().isEmpty() ? 0
                : com.surprising.product.api.utils.InstrumentIds.parse(requested.instrumentId());
        long before = requested.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : requested.beforeTriggerOrderId();
        Iterable<Long> source = requested.expiresBeforeEpochMillis() > 0
                ? owner.triggerOrderIndex().expired(requested.expiresBeforeEpochMillis(), requested.limit())
                : requested.instrumentId().isEmpty()
                ? requested.status() != null ? owner.triggerOrderIndex().ids(requested.status())
                : message.header().userId() == 0 ? requested.beforeTriggerOrderId() == 0
                        ? owner.triggerOrderIndex().ids() : owner.triggerOrderIndex().idsBefore(before)
                        : owner.triggerOrderIndex().ids(message.header().userId())
                : requested.status() == null ? owner.triggerOrderIndex().ids(requested.instrumentId())
                : owner.triggerOrderIndex().ids(requested.instrumentId(), requested.status());
        var selected = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        var users = new org.eclipse.collections.impl.list.mutable.primitive.LongArrayList();
        long laneMask = 0;
        int scanned = 0;
        for (long id : source) {
            if (++scanned > OperationalQueries.MAX_INDEX_SCAN)
                throw new QueryTooLargeException();
            var route = owner.triggerOrderIndex().route(id);
            if (route == null || requested.beforeTriggerOrderId() != 0 && id >= before || requested.triggerOrderId() != 0 && requested.triggerOrderId() != id
                    || message.header().userId() != 0 && message.header().userId() != route.userId()
                    || requestedInstrument != 0 && requestedInstrument != route.instrumentId()
                    || requested.status() != null && requested.status() != route.status()
                    || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY && !route.status().open()) continue;
            selected.add(id); users.add(route.userId());
            laneMask |= owner.accountBook().topology().accountLaneMask(route.userId());
            if (selected.size() == Math.min(requested.limit(), OperationalQueries.MAX_QUERY_ENTITIES)) break;
        }
        if (selected.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(List.of()));
        var query = new AccountQuery(owner, owner.appliedCommandCount(), message.header().messageType(),
                0, 0, null, laneMask, null, null);
        query.triggerPage = new TriggerQueryPage(selected.toArray(), users.toArray(),
                new com.surprising.aeron.protocol.CoreTriggerOrderStateView[selected.size()]);
        return dispatchAccountQuery(message, laneMask, query);
    }

    /** 按算法索引选父单，或精确选择仍保留的父单编号，固定用户路由及原顺序，再交给所属 Lane 汇总子单进度。 */
    private CoreResponse beginAlgoAccountQuery(CoreMessage message) {
        var requested = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
        var ids = requested.algoOrderId() == 0
                ? owner.algoOrderIndex().query(requested.userId(), requested.instrumentId(), requested.dueAtEpochMillis(),
                        requested.limit())
                : owner.algoOrderIndex().value(requested.algoOrderId()) == null ? List.<Long>of() : List.of(requested.algoOrderId());
        if (ids.isEmpty()) return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(),
                com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(List.of()));
        long[] selectedIds = new long[ids.size()], users = new long[ids.size()];
        long laneMask = 0;
        for (int index = 0; index < ids.size(); index++) {
            long selected = ids instanceof com.surprising.aeron.support.collection.ImmutableLongArrayList primitive
                    ? primitive.valueAt(index) : ids.get(index);
            var route = owner.algoOrderIndex().value(selected);
            if (route == null) throw new IllegalStateException("algo query route is missing");
            selectedIds[index] = selected; users[index] = route.userId();
            laneMask |= owner.accountBook().topology().accountLaneMask(route.userId());
        }
        var page = new AlgoQueryPage(selectedIds, users,
                new com.surprising.aeron.protocol.CoreAlgoOrderView[selectedIds.length]);
        var query = new AccountQuery(owner, owner.appliedCommandCount(), CoreMessageType.ALGO_ORDER_QUERY,
                0, 0, null, laneMask, null, page);
        return dispatchAccountQuery(message, laneMask, query);
    }

    private CoreResponse dispatchAccountQuery(CoreMessage message, long laneMask, AccountQuery query) {
        registerQuery(message, query);
        try {
            if (!owner.accountBook().dispatchAccountQuery(laneMask, query)) {
                pendingQueries.remove(message.header().commandId());
                return owner.rejected(CoreResultCode.MATCHING_REJECTED);
            }
        } catch (RuntimeException failure) {
            pendingQueries.remove(message.header().commandId());
            throw failure;
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }

    static CoreResponse missingAccountResponse(CoreMessageType type, long commandCount) {
        if (type == CoreMessageType.USER_STATE_HASH_QUERY || type == CoreMessageType.ORDER_STATE_HASH_QUERY)
            return new CoreResponse(ResponseStatus.OK, commandCount, CoreStateQueryCodec.encodeStateHash(0));
        return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.ENTITY_NOT_FOUND, commandCount);
    }


    /** 账本邮箱暂满时保留的唯一强平工作查询，Owner 在消费入口重试投递，成功后清空。 */
    private LiquidationWorkQuery deferredLiquidationWorkQuery;

    /** 选择强平工作候选，保险页保留游标外计划以计算全局分配。 */
    public CoreResponse beginLiquidationWorkQuery(CoreMessage message, CoreLiquidationWorkView.Query requested) {
        if (deferredLiquidationWorkQuery != null) return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        var candidates = requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                ? owner.liquidationIndex().activeIds()
                : owner.liquidationIndex().activeIds().tailSet(requested.afterLiquidationId(), false);
        if (requested.purpose() == CoreLiquidationWorkView.Purpose.INSURANCE
                && candidates.size() > OperationalQueries.MAX_INDEX_SCAN)
            throw new QueryTooLargeException();
        var query = new LiquidationWorkQuery(owner, owner.appliedCommandCount(), requested, candidates);
        registerQuery(message, query);
        if (query.laneMask != 0 && !owner.accountBook().dispatchAccountQuery(query.laneMask, query)) {
            pendingQueries.remove(message.header().commandId());
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        if (query.ready()) query.apply(owner.accountBook().systemFunds());
        else deferredLiquidationWorkQuery = query;
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING, owner.appliedCommandCount());
    }


    /** 在 Owner 上同步读取财库、未平仓量或合约维护页，沿用查询预算和业务拒绝码。 */
    public CoreResponse queryLedger(CoreMessageType type,
            com.surprising.aeron.protocol.CoreMaintenanceCodec.Query page,
            com.surprising.aeron.protocol.CoreInstrumentMaintenance maintenance) {
        long commandCount = owner.appliedCommandCount();
        SystemFunds ledger = owner.accountBook().systemFunds();
        CoreResponse response;
        try {
            byte[] encoded;
            if (type == CoreMessageType.OPEN_INTEREST_QUERY) {
                var totals = owner.openInterestIndex().totals();
                if (totals.size() > OperationalQueries.MAX_QUERY_ENTITIES)
                    throw new QueryTooLargeException();
                var views = new ArrayList<com.surprising.aeron.protocol.CoreOpenInterestView>(totals.size());
                for (var entry : totals.entrySet()) {
                    views.add(new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()));
                }
                encoded = com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views);
            } else if (type == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
                var users = new ArrayList<Long>(page.limit());
                boolean more = false;
                long next = owner.positionUserIndex().higherUserId(page.instrumentId(), page.afterUserId());
                while (next != 0) {
                    if (users.size() == page.limit()) { more = true; break; }
                    users.add(next);
                    next = owner.positionUserIndex().higherUserId(page.instrumentId(), next);
                }
                encoded = com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                        new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(maintenance, users, more));
            } else encoded = CoreStateQueryCodec.encodeTreasuryState(
                    OperationalQueries.systemFundsAssets(ledger, owner.identities()));
            response = new CoreResponse(ResponseStatus.OK, commandCount, encoded);
        } catch (QueryTooLargeException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.QUERY_RESPONSE_TOO_LARGE, commandCount);
        } catch (IllegalArgumentException failure) {
            response = new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED, CoreResultCode.INVALID_COMMAND, commandCount);
        }
        return response;
    }

    /** 解码单币对盘口查询，在对应 Matcher 提交栅栏之后读取层级。异步完成或失败分别登记，Owner 先返回待完成。 */
    public CoreResponse beginBookQuery(CoreMessage message) {
        if (message.payloadLength() == 0) {
            throw new IllegalArgumentException("single-instrumentId book query payload is required");
        }
        var query = CoreStateQueryCodec.decodeOrderBookQuery(message.payloadUnsafe());
        var result = new CompletableFuture<QueryResult>();
        registerQuery(message, result);
        try {
            owner.matcherPipeline().readAtSubmissionFence(owner.matchingAdapter().matcherShardId(query.instrumentId()),
                    () -> owner.matchingAdapter().orderBookLevels(query.instrumentId(), query.depth()))
                    .whenComplete((levels, failure) -> {
                        if (failure != null) result.completeExceptionally(failure);
                        else result.complete(QueryResult.single(levels));
                    });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING,
                owner.appliedCommandCount());
    }

    /** 已有快照编号时校验会话和深度并续页。新请求异步读取一个或多个 Matcher 的初始化快照，合并后交 Owner 建立有界分页会话。 */
    public CoreResponse beginBookBootstrapQuery(CoreMessage message) {
        CoreOrderBookBootstrapQuery query = CoreStateQueryCodec.decodeOrderBookBootstrapQuery(message.payloadUnsafe());
        if (!query.snapshotId().isEmpty()) {
            BookBootstrapSession session = bookBootstrapSessions.get(query.snapshotId());
            if (session == null || session.depth() != query.depth()) {
                return owner.rejected(CoreResultCode.BOOK_BOOTSTRAP_CURSOR_INVALID);
            }
            return bootstrapPageResponse(session, query);
        }
        var result = new CompletableFuture<QueryResult>();
        String snapshotId = message.header().commandId().toString();
        registerQuery(message, result);
        try {
            var snapshot = owner.matchingAdapter().topology().matchingEngineCount() == 1
                    ? owner.matcherPipeline().readAtSubmissionFence(0,
                    () -> owner.matchingAdapter().orderBookBootstrapShard(0, query.depth()))
                    : owner.matcherPipeline().readEachAsync(
 shardId -> owner.matchingAdapter().orderBookBootstrapShard(shardId, query.depth()))
                    .thenApply(owner.matchingAdapter()::mergeBookBootstrapShards);
            snapshot.whenComplete((value, failure) -> {
                if (failure != null) result.completeExceptionally(failure);
                else result.complete(QueryResult.bootstrap(
                        snapshotId, query, value));
            });
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
        }
        return new CoreResponse(ResponseStatus.OK, ResponseStatus.OK, com.surprising.aeron.protocol.CoreResultCode.MATCHING_PENDING,
                owner.appliedCommandCount());
    }

    private void registerQuery(CoreMessage message, CompletableFuture<QueryResult> query) {
        if (pendingQueries.putIfAbsent(message.header().commandId(), query) != null)
            throw new IllegalArgumentException("query is already pending");
    }

    public boolean hasPendingQueries() { return !pendingQueries.isEmpty(); }

    public boolean hasPendingQuery(UUID commandId) { return pendingQueries.containsKey(commandId); }

    /** 消费者停止后解除 Owner 对查询任务的引用。 */
    public void clearPendingQueries() {
        pendingQueries.clear();
        deferredLiquidationWorkQuery = null;
    }

    /** Owner 继续必要投递，并按外部请求 UUID 一次性消费结果。 */
    public CoreResponse takeQueryResult(UUID commandId) {
        var query = pendingQueries.get(commandId);
        if (query == null) return null;
        if (query instanceof RiskAccountQuery riskQuery) riskQuery.dispatchAccounts();
        if (query == deferredLiquidationWorkQuery && deferredLiquidationWorkQuery.ready()) {
            deferredLiquidationWorkQuery.apply(owner.accountBook().systemFunds());
            deferredLiquidationWorkQuery = null;
        }
        if (!query.isDone()) return null;
        pendingQueries.remove(commandId);
        QueryResult completed;
        try {
            completed = query.join();
        } catch (java.util.concurrent.CompletionException failure) {
            if (failure.getCause() instanceof Error fatal) throw fatal;
            return owner.rejected(CoreResultCode.MATCHING_REJECTED);
        }
        if (completed.encodedResponse() != null) return completed.encodedResponse();
        long exportSequence = owner.commits.publication.publishedSequence();
        if (completed.bootstrapSnapshot() == null) {
            var view = new com.surprising.aeron.protocol.CoreOrderBookView(exportSequence, completed.levels());
            byte[] encoded = CoreStateQueryCodec.encodeOrderBookView(view);
            return boundedBookResponse(completed.levels().size(), encoded);
        }
        BookBootstrapSession session = BookBootstrapSession.create(completed.snapshotId(), exportSequence,
                completed.bootstrapQuery().depth(), completed.bootstrapSnapshot());
        while (bookBootstrapSessions.size() >= TradingCore.Limits.MAX_BOOK_BOOTSTRAP_SNAPSHOTS) {
            bookBootstrapSessions.remove(bookBootstrapSessions.keySet().iterator().next());
        }
        bookBootstrapSessions.put(session.snapshotId(), session);
        return bootstrapPageResponse(session, completed.bootstrapQuery());
    }

    /** 校验币对游标属于固定会话，按币对文本顺序选择其后最多 limit 个币对并汇总层级，计算续页游标，编码后检查条数与字节上限。 */
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

    /** 统一检查盘口层级数量和编码字节上限，超限返回专用错误，否则按当前已应用命令计数构造成功响应。 */
    CoreResponse boundedBookResponse(int levelCount, byte[] encoded) {
        if (levelCount > TradingCore.Limits.MAX_BOOK_RESPONSE_LEVELS || encoded.length > TradingCore.Limits.MAX_BOOK_RESPONSE_BYTES) {
            return owner.rejected(CoreResultCode.BOOK_QUERY_RESPONSE_TOO_LARGE);
        }
        return new CoreResponse(ResponseStatus.OK, owner.appliedCommandCount(), encoded);
    }

    /** 已完成查询的交接结果：可直接携带已编码响应，也可携带单币对盘口层级或多币对初始化快照，Owner 消费时选择对应协议。 */
    record QueryResult(
            List<com.surprising.aeron.protocol.CoreBookLevelView> levels,
            String snapshotId,
            CoreOrderBookBootstrapQuery bootstrapQuery,
            OrderBookDepthSnapshot bootstrapSnapshot,
            CoreResponse encodedResponse) {

        /** 保存尚待 Owner 包装的盘口查询结果，已编码响应分支设为 null。 */
        QueryResult(List<com.surprising.aeron.protocol.CoreBookLevelView> levels, String snapshotId,
                           CoreOrderBookBootstrapQuery bootstrapQuery, OrderBookDepthSnapshot bootstrapSnapshot) {
            this(levels, snapshotId, bootstrapQuery, bootstrapSnapshot, null);
        }

        /** 把所属读取线程已经编码的响应包装为可一次消费的完成结果，无须 Owner 再读实体。 */
        static QueryResult encoded(CoreResponse response) { return new QueryResult(List.of(), "", null, null, response); }

        /** 复制单币对盘口层级列表作为不可修改的查询交接内容，Owner 稍后补导出水位并编码。 */
        static QueryResult single(
                List<com.surprising.aeron.protocol.CoreBookLevelView> levels) {
            return new QueryResult(List.copyOf(levels), "", null, null);
        }

        /** 保留多币对初始化查询条件及 Matcher 快照，Owner 消费后创建分页会话。 */
        static QueryResult bootstrap(
                String snapshotId,
                CoreOrderBookBootstrapQuery query,
                OrderBookDepthSnapshot snapshot) {
            return new QueryResult(List.of(), snapshotId, query, snapshot);
        }
    }

    /** 多币对盘口初始化的分页会话，固定快照编号、导出水位、深度和按币对文本排序的只读层级映射，后续页不能混入另一份快照。 */
    record BookBootstrapSession(
            String snapshotId,
            long exportSequence,
            int depth,
            NavigableMap<String, List<com.surprising.aeron.protocol.CoreBookLevelView>> symbols) {

        /** 按币对文本建立有序分组，把层级归入已有币对并拒绝未知引用。 */
        static BookBootstrapSession create(
                String snapshotId,
                long exportSequence,
                int depth,
                OrderBookDepthSnapshot snapshot) {
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
