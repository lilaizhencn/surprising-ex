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
 * 查询协议路由：读取已有路由索引并向实际账户、撮合器或账本所有者派发查询。
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
                && OrderBookQueryService.accountQuery(message.header().messageType())) {
            try { return runtime.bookQueries.beginAccountQuery(message); }
            catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException tooLarge) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException invalid) { return runtime.rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.STATE_HASH_QUERY
                || message.header().messageType() == CoreMessageType.BUSINESS_STATE_HASH_QUERY)) {
            // Full hashes are explicit audit queries and are calculated from authoritative state.
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                    CoreStateQueryCodec.encodeStateHash(runtime.currentBusinessStateHash()));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.LANE_METRICS_QUERY) {
            if (message.payloadUnsafe().length != 0) return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                    runtime.encodeLaneMetrics());
        }
        if (message.header().messageType() == CoreMessageType.INSTRUMENT_MAINTENANCE_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreMaintenanceCodec.decodeQuery(message.payloadUnsafe());
                var instrument = runtime.runtimeState.instrument(query.instrumentId());
                if (instrument == null) return runtime.rejected(CoreResultCode.ENTITY_NOT_FOUND);
                var users = new java.util.ArrayList<Long>(query.limit());
                boolean more = false;
                for (long userId : runtime.positionUserIndex.usersAfter(query.instrumentId(), query.afterUserId())) {
                    if (users.size() == query.limit()) { more = true; break; }
                    users.add(userId);
                }
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        com.surprising.aeron.protocol.CoreMaintenanceCodec.encodePage(
                                new com.surprising.aeron.protocol.CoreMaintenanceCodec.Page(
                                        instrument.maintenance(), users, more)));
            } catch (IllegalArgumentException | java.nio.BufferUnderflowException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.COMMAND_RESULT_QUERY) {
            try {
                UUID commandId = CoreStateQueryCodec.decodeCommandResultQuery(message.payloadUnsafe());
                StoredResult result = runtime.resultLedger.get(commandId);
                if (result == null) {
                    return new CoreResponse(ResponseStatus.REJECTED, ResponseStatus.REJECTED,
                            CoreResultCode.RESULT_UNKNOWN_OUTSIDE_RETENTION, runtime.appliedCommandCount,
                            runtime.EMPTY_RESPONSE_DATA);
                }
                runtime.responseArena.retain(result.responseDataUnsafe());
                return CoreResponse.owned(ResponseStatus.OK, result.status(), result.resultCode(),
                        result.appliedCommandCount(),
                        result.responseDataUnsafe(), result.responseDataOffsetUnsafe(), result.responseDataLength());
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && (message.header().messageType() == CoreMessageType.TRIGGER_ORDER_QUERY
                || message.header().messageType() == CoreMessageType.USER_OPEN_TRIGGER_ORDERS_QUERY)) {
            try { return runtime.bookQueries.beginTriggerQuery(message);
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
                if (runtime.runtimeState.systemLedgerStarted()) return runtime.bookQueries.beginTreasuryQuery(message);
                var views = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.treasuryAssets(
                        runtime.runtimeState, runtime.identities);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        CoreStateQueryCodec.encodeTreasuryState(views));
            } catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException exception) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.FUNDING_PROGRESS_QUERY) {
            try {
                String instrumentId = CoreStateQueryCodec.decodeFundingProgressQuery(message.payloadUnsafe());
                CoreFundingProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .fundingProgress(runtime.runtimeState, runtime.identities, instrumentId);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        CoreFundingProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.SETTLEMENT_PROGRESS_QUERY) {
            try {
                String instrumentId = CoreStateQueryCodec.decodeSettlementProgressQuery(message.payloadUnsafe());
                CoreSettlementProgressView view = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService
                        .settlementProgress(runtime.runtimeState, runtime.identities, instrumentId);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        CoreSettlementProgressCodec.encode(view));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && OrderBookQueryService.riskAccountQuery(message.header().messageType())) {
            try { return runtime.bookQueries.beginRiskAccountQuery(message); }
            catch (com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.QueryTooLargeException failure) {
                return runtime.rejected(CoreResultCode.QUERY_RESPONSE_TOO_LARGE);
            } catch (IllegalArgumentException failure) { return runtime.rejected(CoreResultCode.INVALID_COMMAND); }
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.RISK_SCAN_CONTROL_QUERY) {
            if (message.payloadUnsafe().length != 0) return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
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
            return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                    com.surprising.aeron.protocol.CoreOpenInterestCodec.encode(views));
        }
        if (message.header().kind() == WireMessageKind.QUERY
                && message.header().messageType() == CoreMessageType.CANCEL_ALL_AFTER_QUERY) {
            try {
                var query = com.surprising.aeron.protocol.CoreCancelAllAfterCodec.decodeQuery(message.payloadUnsafe());
                var keys = runtime.cancelAllAfterIndex.query(query.userId(), query.symbolScope(), query.dueAtEpochMillis(),
                        query.limit(), runtime.runtimeState::cancelAllAfterTimer);
                var values = com.surprising.aeron.service.state.query.RuntimeOperationalQueryService.cancelAllAfter(
                        runtime.runtimeState, keys);
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
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
                if (runtime.runtimeState.systemLedgerStarted()) {
                    return runtime.bookQueries.beginLiquidationWorkQuery(message, query);
                }
                var work = com.surprising.aeron.service.state.query.RuntimeLiquidationQueryService.work(
                        runtime.runtimeState, runtime.identities, runtime.productLine, query, candidates,
                        runtime.liquidationIndex.activeIds());
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
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
                return runtime.bookQueries.beginPreflightQuery(message, clusterTimestamp);
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
                if (runtime.runtimeState.asynchronousCommands())
                    return runtime.bookQueries.beginPendingTransferQuery(message, limit);
                var transfers = runtime.runtimeState.pendingTransfers(limit).stream()
                        .map(value -> new com.surprising.aeron.protocol.CorePendingTransferView(
                                value.userId(), value.command()))
                        .toList();
                return new CoreResponse(ResponseStatus.OK, runtime.appliedCommandCount,
                        com.surprising.aeron.protocol.CorePendingTransferCodec.encode(transfers));
            } catch (IllegalArgumentException exception) {
                return runtime.rejected(CoreResultCode.INVALID_COMMAND);
            }
        }
        return null;
    }

}
