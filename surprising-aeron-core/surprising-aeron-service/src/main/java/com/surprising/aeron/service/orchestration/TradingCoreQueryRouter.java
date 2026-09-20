package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.CoreFundingProgressCodec;
import com.surprising.aeron.protocol.CoreFundingProgressView;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResponse;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.CoreSettlementProgressCodec;
import com.surprising.aeron.protocol.CoreSettlementProgressView;
import com.surprising.aeron.protocol.CoreStateQueryCodec;
import com.surprising.aeron.protocol.CoreRiskScanControlCodec;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.WireMessageKind;
import com.surprising.aeron.service.orchestration.CommandResultLedger.StoredResult;
import com.surprising.aeron.service.state.ResolvedPlaceOrder;
import com.surprising.aeron.service.exception.CoreStateRejectedException;
import com.surprising.aeron.service.state.admission.CoreOrderDecisionResolver;
import java.util.List;
import java.util.UUID;

/**
 * 查询协议路由：只读取交易 Owner 的权威状态和索引，生成同步响应或启动盘口异步查询。
 *
 * <p>该类不保存交易状态，也不参与命令准入、撮合提交或资金变更。</p>
 */
final class TradingCoreQueryRouter {
    private final TradingCoreRuntime runtime;

    TradingCoreQueryRouter(TradingCoreRuntime runtime) {
        this.runtime = runtime;
    }

    /**
     * Returning {@code null} means the message is a command and must continue to command admission.
     */
    CoreResponse apply(CoreMessage message, long clusterTimestamp) {
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            // Full hashes are explicit audit queries. The hot-path cached value is a snapshot
            // audit anchor and does not track mutable account/order changes.
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                    runtime.canonicalBusinessStateHash(runtime.tradingState().businessStateHash()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadUnsafe().length != 0) return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                    runtime.encodeLaneMetrics());
        }
        if (message.header().messageType() == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                var instrument = runtime.runtimeState.instrument(query.symbol());
                if (instrument == null) return runtime.rejected(CoreResultCode.ENTITY_NOT_FOUND);
                var users = new java.util.ArrayList<Long>(query.limit());
                boolean more = false;
                for (long userId : runtime.positionUserIndex.usersAfter(query.symbol(), query.afterUserId())) {
                    if (users.size() == query.limit()) { more = true; break; }
                    users.add(userId);
                }
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                                new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(
                                        instrument.maintenance(), users, more)));
            } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_HASH_QUERY) {
            var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.userState(
                    runtime.runtimeState, runtime.identities, message.header().userId());
            if (query.tooLarge()) return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                    query.found() ? query.stateHash() : 0);
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                StoredResult result = runtime.resultLedger.get(commandId);
                if (result == null) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION, runtime.appliedCommandCount,
                            runtime.cachedBusinessStateHash, runtime.EMPTY_RESPONSE_DATA);
                }
                runtime.responseArena.retain(result.responseDataUnsafe());
                return CoreResponse.owned(ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(), result.stateHash(),
                        result.responseDataUnsafe(), result.responseDataOffsetUnsafe(), result.responseDataLength());
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_HASH_QUERY) {
            try {
                var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.orderState(
                        runtime.runtimeState, runtime.identities,
                        TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()));
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        query.found() ? query.stateHash() : 0);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_STATE_QUERY) {
            return runtime.userStateResponse(message.header().userId());
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_STATE_QUERY) {
            try {
                return runtime.orderStateResponse(TradingCommandCodec.decodeOrderStateQuery(message.payloadUnsafe()));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CLIENT_ORDER_STATE_QUERY) {
            try {
                var query = com.surprising.aeron.service.state.query.RuntimeStateQueryService.clientOrderState(
                        runtime.runtimeState, runtime.identities, message.header().userId(),
                        CoreStateQueryCodec.decodeClientOrderStateQuery(message.payloadUnsafe()));
                return runtime.orderStateResponse(query);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.USER_OPEN_ORDERS_QUERY) {
            try {
                var query = CoreStateQueryCodec.decodeOpenOrdersQuery(message.payloadUnsafe());
                long beforeOrderId = query.beforeOrderId() == 0 ? Long.MAX_VALUE : query.beforeOrderId();
                long requestedUserId = message.header().userId();
                var page = runtime.activeOrderIndex.page(requestedUserId, query.symbol(), beforeOrderId, query.limit());
                var orders = page.orderIds().stream()
                        .map(orderId -> com.surprising.aeron.service.state.query.RuntimeStateQueryService.orderState(
                                runtime.runtimeState, runtime.identities, orderId))
                        .filter(com.surprising.aeron.service.state.query.RuntimeStateQueryService.OrderQueryResult::found)
                        .map(com.surprising.aeron.service.state.query.RuntimeStateQueryService.OrderQueryResult::view)
                        .toList();
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeOpenOrders(
                                new com.surprising.aeron.protocol.CoreOpenOrdersView(orders)));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try {
                var query = com.surprising.aeron.protocol.CoreTriggerOrderCodec.decodeQuery(message.payloadUnsafe());
                long before = query.beforeTriggerOrderId() == 0 ? Long.MAX_VALUE : query.beforeTriggerOrderId();
                Iterable<Long> source = query.expiresBeforeEpochMillis() > 0
                        ? runtime.triggerOrderIndex.expired(query.expiresBeforeEpochMillis(), query.limit())
                        : query.symbol().isEmpty()
                        ? (query.status() != null
                        ? runtime.triggerOrderIndex.ids(query.status())
                        : message.header().userId() == 0 ? runtime.triggerOrderIndex.ids()
                        : runtime.triggerOrderIndex.ids(message.header().userId()))
                        : (query.status() == null ? runtime.triggerOrderIndex.ids(query.symbol())
                        : runtime.triggerOrderIndex.ids(query.symbol(), query.status()));
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.triggerOrders(
                        runtime.runtimeState, source, message.header().userId(), query.symbol(), query.status(),
                        query.triggerOrderId(), before,
                        message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY,
                        query.limit());
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreTriggerOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.BOOK_STATE_QUERY) {
            try {
                return runtime.bookQueries.beginBookQuery(message);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_BOOK_BOOTSTRAP_QUERY) {
            try {
                return runtime.bookQueries.beginBookBootstrapQuery(message);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.TREASURY_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.treasuryAssets(
                        runtime.runtimeState, runtime.identities);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        CoreStateQueryCodec.encodeTreasuryState(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                CoreFundingProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .fundingProgress(runtime.runtimeState, runtime.identities, symbol);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String symbol = CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                CoreSettlementProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .settlementProgress(runtime.runtimeState, runtime.identities, symbol);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ADL_CANDIDATE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAdlQueryCodec.decodeQuery(message.payloadUnsafe());
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAdlQueryCodec.encodeCandidates(
                                com.surprising.aeron.service.state.query.RuntimeRiskQueryService.adlCandidates(
                                        runtime.runtimeState, runtime.identities, query.asset(),
                                        runtime.adlPositionIndex.positions(query.asset()), query.limit())));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_STATE_QUERY) {
            try {
                var views = com.surprising.aeron.service.state.query.RuntimeRiskQueryService.snapshots(
                        runtime.runtimeState, runtime.identities, message.header().userId());
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreRiskQueryCodec.encode(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadUnsafe().length != 0) return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                    CoreRiskScanControlCodec.encodeView(runtime.runtimeState.riskScanControl()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.OPEN_INTEREST_QUERY) {
            if (runtime.openInterestIndex.totals().size()
                    > com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.MAX_QUERY_ENTITIES) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
            var views = runtime.openInterestIndex.totals().entrySet().stream()
                    .map(entry -> new com.surprising.aeron.protocol.CoreOpenInterestView(
                            entry.getKey(), entry.getValue().longQuantity(), entry.getValue().shortQuantity()))
                    .toList();
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                    com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ALGO_ORDER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreAlgoOrderCodec.decodeQuery(message.payloadUnsafe());
                var algoIds = query.algoOrderId() != 0
                        ? List.of(query.algoOrderId())
                        : runtime.algoOrderIndex.query(query.userId(), query.symbol(), query.dueAtEpochMillis(),
                                query.limit(), runtime.runtimeState::algoOrder);
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.algoOrders(
                        runtime.runtimeState, algoIds);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreAlgoOrderCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                var keys = runtime.cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), runtime.runtimeState::cancelAllAfterTimer);
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.cancelAllAfter(
                        runtime.runtimeState, keys);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreCancelAllAfterCodec.encodeList(values));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LIQUIDATION_WORK_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreLiquidationWorkCodec.decodeQuery(message.payloadUnsafe());
                if (query.productLine() != runtime.productLine) {
                    return runtime.rejected(CoreResultCode.PRODUCT_LINE_MISMATCH);
                }
                java.util.NavigableSet<Long> candidates = runtime.liquidationIndex.activeIds()
                        .tailSet(query.afterLiquidationId(), false);
                var work = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService.work(
                        runtime.runtimeState, runtime.identities, runtime.productLine, query, candidates,
                        runtime.liquidationIndex.activeIds());
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreLiquidationWorkCodec.encodeWork(work));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.ORDER_PREFLIGHT_QUERY) {
            try {
                var command = TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe());
                runtime.requireOrderIdentityAvailable(message.header().userId(), command);
                ResolvedPlaceOrder resolved = CoreOrderDecisionResolver.resolve(runtime.runtimeState,
                        runtime.identities, message.header().userId(), command, clusterTimestamp);
                long reservedUnits = com.surprising.aeron.service.state.RuntimeOrderAdmission.requiredReservation(
                        runtime.runtimeState, runtime.identities, message.header().userId(), resolved,
                        runtime.openInterestIndex.openInterestSteps(command.symbol()), runtime.activeOrderIndex);
                var view = new com.surprising.aeron.protocol.CoreOrderPreflightView(
                        resolved.reservationAsset(), reservedUnits);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CoreOrderPreflightCodec.encode(view));
            } catch (CoreStateRejectedException exception) {
                return runtime.rejected(CoreResultCode.fromRejectionCode(exception.code()));
            } catch (ArithmeticException exception) {
                return runtime.rejected(CoreResultCode.ARITHMETIC_OVERFLOW);
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.PENDING_TRANSFER_QUERY) {
            try {
                int limit = com.surprising.aeron.protocol.CorePendingTransferCodec.decodeQuery(
                        message.payloadUnsafe());
                var transfers = runtime.runtimeState.pendingTransfers(limit).stream()
                        .map(value -> new com.surprising.aeron.protocol.CorePendingTransferView(
                                value.userId(), value.command()))
                        .toList();
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount, runtime.cachedBusinessStateHash,
                        com.surprising.aeron.protocol.CorePendingTransferCodec.encode(transfers));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        return null;
    }

}
