package com.surprising.liquidation.provider.service;

import com.surprising.aeron.client.AeronClientPool;
import com.surprising.aeron.protocol.ContinueRiskScanCommand;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultCodec;
import com.surprising.aeron.protocol.CoreLiquidationBatchResultView;
import com.surprising.aeron.protocol.CoreLiquidationActionView;
import com.surprising.aeron.protocol.CoreLiquidationProgressCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkCodec;
import com.surprising.aeron.protocol.CoreLiquidationWorkView;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreResultCode;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchAction;
import com.surprising.aeron.protocol.ExecuteLiquidationBatchCommand;
import com.surprising.aeron.protocol.ExecuteLiquidationCommand;
import com.surprising.aeron.protocol.ResponseStatus;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.liquidation.provider.config.LiquidationProperties;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.stereotype.Service;

@Service
public class LiquidationAeronGateway implements AutoCloseable {

    private final LiquidationProperties properties;
    private final AeronClientPool clients;

    public LiquidationAeronGateway(LiquidationProperties properties) {
        this.properties = properties;
        LiquidationProperties.Aeron aeron = properties.getAeron();
        clients = new AeronClientPool("liquidation", properties.getProductLine(), aeron.getHostnames(),
                aeron.getEgressHostname(), aeron.getResponseTimeout(), aeron.getClientConnections());
    }

    public CoreLiquidationWorkView work(int limit) {
        var response = clients.query(CoreMessageType.LIQUIDATION_WORK_QUERY, UUID.randomUUID(), 0,
                CoreLiquidationWorkCodec.encodeQuery(limit));
        if (response.status() != ResponseStatus.OK) {
            throw new IllegalStateException(response.resultCode() + ": Aeron liquidation work query failed");
        }
        return CoreLiquidationWorkCodec.decodeWork(response.data());
    }

    public CoreLiquidationBatchResultView executeBatch(CoreLiquidationWorkView work,
                                                       long liquidationFeeRatePpm,
                                                       int maxRiskScanUsers) {
        if (work == null) throw new IllegalArgumentException("liquidation work is required");
        List<ExecuteLiquidationBatchAction> actions = work.actions().stream()
                .map(action -> new ExecuteLiquidationBatchAction(action.liquidationId(), action.userId(), action.symbol(),
                        action.instrumentVersion(), action.triggerPriceSequence(), action.markPriceTicks(),
                        action.cursorOrderId()))
                .toList();
        var command = new ExecuteLiquidationBatchCommand(actions, ExecuteLiquidationBatchCommand.MAX_CANCEL_ORDERS,
                liquidationFeeRatePpm, work.riskScanContinuation(),
                work.riskScanPending() ? maxRiskScanUsers : 0);
        byte[] payload = TradingCommandCodec.encodeExecuteLiquidationBatch(command);
        var response = clients.command(CoreMessageType.EXECUTE_LIQUIDATION_BATCH, stableBatchCommandId(payload), 0,
                payload);
        if (response.commandStatus() != ResponseStatus.APPLIED && response.commandStatus() != ResponseStatus.DUPLICATE) {
            throw new IllegalStateException(response.resultCode() + ": Aeron liquidation batch rejected");
        }
        return CoreLiquidationBatchResultCodec.decode(response.data());
    }

    public void continueRiskScan(int maxUsers) {
        var response = clients.command(CoreMessageType.CONTINUE_RISK_SCAN, UUID.randomUUID(), 0,
                TradingCommandCodec.encodeContinueRiskScan(new ContinueRiskScanCommand(maxUsers)));
        if (response.commandStatus() != ResponseStatus.APPLIED) {
            throw new IllegalStateException(response.resultCode() + ": Aeron risk scan continuation rejected");
        }
    }

    public CoreResultCode execute(CoreLiquidationActionView action, long liquidationFeeRatePpm) {
        long cursorOrderId = 0;
        for (;;) {
            var response = executeStep(action, liquidationFeeRatePpm, cursorOrderId);
            if (response.commandStatus() != ResponseStatus.APPLIED) return rejectedResult(response);
            var progress = CoreLiquidationProgressCodec.decode(response.data());
            if (progress.complete()) return CoreResultCode.NONE;
            if (progress.nextCursorOrderId() <= cursorOrderId) {
                throw new IllegalStateException("Aeron liquidation cursor did not advance");
            }
            cursorOrderId = progress.nextCursorOrderId();
        }
    }

    public CompletableFuture<List<CoreResultCode>> executeBatch(List<CoreLiquidationActionView> actions,
                                                                  long liquidationFeeRatePpm) {
        if (actions == null || actions.isEmpty()) return CompletableFuture.completedFuture(List.of());
        int width = Math.min(actions.size(), properties.getAeron().getClientConnections());
        List<CoreResultCode> results = new ArrayList<>(java.util.Collections.nCopies(actions.size(), null));
        CompletableFuture<List<CoreResultCode>> completed = new CompletableFuture<>();
        AtomicInteger next = new AtomicInteger();
        AtomicInteger remaining = new AtomicInteger(actions.size());
        for (int index = 0; index < width; index++) {
            submitNext(actions, liquidationFeeRatePpm, results, completed, next, remaining);
        }
        return completed;
    }

    private void submitNext(List<CoreLiquidationActionView> actions, long feeRatePpm,
                            List<CoreResultCode> results, CompletableFuture<List<CoreResultCode>> completed,
                            AtomicInteger next, AtomicInteger remaining) {
        int index = next.getAndIncrement();
        if (index >= actions.size() || completed.isDone()) return;
        executeAsync(actions.get(index), feeRatePpm, 0).whenComplete((result, failure) -> {
            if (failure != null) {
                completed.completeExceptionally(failure);
                return;
            }
            results.set(index, result);
            if (remaining.decrementAndGet() == 0) {
                completed.complete(List.copyOf(results));
                return;
            }
            submitNext(actions, feeRatePpm, results, completed, next, remaining);
        });
    }

    private CompletableFuture<CoreResultCode> executeAsync(CoreLiquidationActionView action,
                                                            long liquidationFeeRatePpm,
                                                            long cursorOrderId) {
        return clients.commandAsync(CoreMessageType.EXECUTE_LIQUIDATION,
                        stableCommandId(action, liquidationFeeRatePpm, cursorOrderId), action.userId(),
                        TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                                action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(),
                                liquidationFeeRatePpm, cursorOrderId, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS)))
                .thenCompose(response -> {
                    if (response.commandStatus() != ResponseStatus.APPLIED) {
                        return CompletableFuture.completedFuture(rejectedResult(response));
                    }
                    var progress = CoreLiquidationProgressCodec.decode(response.data());
                    if (progress.complete()) return CompletableFuture.completedFuture(CoreResultCode.NONE);
                    if (progress.nextCursorOrderId() <= cursorOrderId) {
                        return CompletableFuture.failedFuture(new IllegalStateException(
                                "Aeron liquidation cursor did not advance"));
                    }
                    return executeAsync(action, liquidationFeeRatePpm, progress.nextCursorOrderId());
                });
    }

    private com.surprising.aeron.protocol.CoreResponse executeStep(CoreLiquidationActionView action,
                                                                    long liquidationFeeRatePpm,
                                                                    long cursorOrderId) {
        UUID commandId = stableCommandId(action, liquidationFeeRatePpm, cursorOrderId);
        return clients.command(CoreMessageType.EXECUTE_LIQUIDATION, commandId, action.userId(),
                TradingCommandCodec.encodeExecuteLiquidation(new ExecuteLiquidationCommand(
                        action.liquidationId(), action.triggerPriceSequence(), action.markPriceTicks(),
                        liquidationFeeRatePpm, cursorOrderId, ExecuteLiquidationCommand.DEFAULT_MAX_ORDERS)));
    }

    private static CoreResultCode rejectedResult(com.surprising.aeron.protocol.CoreResponse response) {
        if (response.resultCode() == CoreResultCode.LIQUIDATION_STATE_CONFLICT
                || response.resultCode() == CoreResultCode.LIQUIDATION_NOT_FOUND
                || response.resultCode() == CoreResultCode.STALE_MARK_PRICE) {
            return response.resultCode();
        }
        throw new IllegalStateException(response.resultCode() + ": Aeron liquidation command rejected");
    }

    UUID stableCommandId(CoreLiquidationActionView action, long liquidationFeeRatePpm) {
        return stableCommandId(action, liquidationFeeRatePpm, 0);
    }

    UUID stableCommandId(CoreLiquidationActionView action, long liquidationFeeRatePpm, long cursorOrderId) {
        String identity = properties.getProductLine() + ":LIQUIDATION:" + action.liquidationId() + ':'
                + action.triggerPriceSequence() + ':' + action.markPriceTicks() + ':' + liquidationFeeRatePpm
                + ':' + cursorOrderId;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    UUID stableBatchCommandId(byte[] canonicalPayload) {
        byte[] productLine = properties.getProductLine().name().getBytes(StandardCharsets.UTF_8);
        ByteBuffer identity = ByteBuffer.allocate(Integer.BYTES + productLine.length + canonicalPayload.length)
                .putInt(productLine.length).put(productLine).put(canonicalPayload);
        return UUID.nameUUIDFromBytes(identity.array());
    }

    @Override
    @PreDestroy
    public void close() {
        clients.close();
    }
}
