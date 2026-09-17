package com.surprising.aeron.service.state;

import com.surprising.aeron.protocol.AdjustInsuranceFundCommand;
import com.surprising.aeron.protocol.BalanceAdjustmentCommand;
import com.surprising.aeron.protocol.CoreAlgoOrderView;
import com.surprising.aeron.protocol.CoreMaintenanceCodec;
import com.surprising.aeron.protocol.CoreTriggerOrderStateView;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.UpsertInstrumentCommand;
import com.surprising.aeron.service.state.admission.AdmissionIdentity;
import com.surprising.aeron.service.state.model.CoreRiskState;
import com.surprising.aeron.service.state.model.CoreTriggerOrderState;
import com.surprising.product.api.ProductLine;

import java.util.UUID;

/**
 * Compatibility entry points for runtime commands.
 *
 * <p>Business state ownership lives in the specific runtime transition class for each state family. This class
 * remains temporarily public because command handlers, commit events and recovery code still share these entry points.
 */
public final class RuntimeCommandProcessor {

    private RuntimeCommandProcessor() {
    }

    public static void adjustBalance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                     long userId, BalanceAdjustmentCommand command) {
        RuntimeAccountStateTransitions.adjustBalance(runtime, identities, userId, command);
    }

    public static boolean transferOut(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                      long userId, TransferFundsCommand command) {
        return RuntimeAccountStateTransitions.transferOut(runtime, identities, userId, command);
    }

    public static void transferIn(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, TransferFundsCommand command) {
        RuntimeAccountStateTransitions.transferIn(runtime, identities, userId, command);
    }

    public static boolean completeTransfer(TradingRuntimeState runtime, long userId, long transferId) {
        return RuntimeAccountStateTransitions.completeTransfer(runtime, userId, transferId);
    }

    public static void updateRiskScanControl(TradingRuntimeState runtime, UpdateRiskScanControlCommand command,
                                             long updatedAtEpochMillis) {
        RuntimeRiskStateTransitions.updateScanControl(runtime, command, updatedAtEpochMillis);
    }

    public static void upsertInstrument(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                        UpsertInstrumentCommand command) {
        RuntimeInstrumentStateTransitions.upsert(runtime, identities, command);
    }

    public static void updateInstrumentMaintenance(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                                   CoreMaintenanceCodec.Command command) {
        RuntimeInstrumentStateTransitions.updateMaintenance(runtime, identities, command);
    }

    public static void adjustInsuranceFund(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                           AdjustInsuranceFundCommand command) {
        RuntimeInsuranceFundStateTransitions.adjust(runtime, identities, command);
    }

    public static void placeOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                  long userId, ResolvedPlaceOrder command, UUID commandId,
                                  long requiredReservation) {
        RuntimeOrderStateTransitions.place(runtime, identities, userId, command, commandId, requiredReservation);
    }

    public static void placeOrderPrepared(
            TradingRuntimeState runtime, long userId, ResolvedPlaceOrder command, UUID commandId,
            long requiredReservation, long clientKey, int symbolId, int assetId) {
        RuntimeOrderStateTransitions.placePrepared(runtime, userId, command, commandId, requiredReservation,
                clientKey, symbolId, assetId);
    }

    /** Trigger claim, OCO cancellations and this reservation share one control Lane task. */
    public static void placeTriggerChildInLane(TradingRuntimeState runtime, long userId,
                                               ResolvedPlaceOrder command, UUID commandId, long coreSequence,
                                               long openInterestSteps, AdmissionIdentity identity, long clientKey,
                                               int assetId) {
        RuntimeOrderStateTransitions.placeTriggerChildInLane(runtime, userId, command, commandId, coreSequence,
                openInterestSteps, identity, clientKey, assetId);
    }

    /** One account task writes the admitted order and its local pending marker. */
    public static void reserveBatchOrderInLane(TradingRuntimeState runtime, long userId,
                                               ResolvedPlaceOrder command, UUID commandId, long requiredReservation,
                                               long clientKey, int assetId, long coreSequence) {
        RuntimeOrderStateTransitions.reserveBatchOrderInLane(runtime, userId, command, commandId,
                requiredReservation, clientKey, assetId, coreSequence);
    }

    public static boolean cancelOrder(TradingRuntimeState runtime, long userId, long orderId) {
        return RuntimeOrderStateTransitions.cancel(runtime, userId, orderId);
    }

    public static void rejectPlaceOrder(TradingRuntimeState runtime, long userId, long orderId,
                                        long coreSequence) {
        RuntimeOrderStateTransitions.reject(runtime, userId, orderId, coreSequence);
    }

    public static void validateOrderStampInputs(long timestamp, long position, Iterable<Long> orderIds) {
        RuntimeOrderCommitStateTransitions.validateStampInputs(timestamp, position, orderIds);
    }

    public static boolean stampChangedOrdersByLane(
            TradingRuntimeState runtime, long timestamp, long clusterPosition,
            Iterable<Long> changedOrderIds, Iterable<Long> changedUserIds) {
        return RuntimeOrderCommitStateTransitions.stampChangedOrdersByLane(
                runtime, timestamp, clusterPosition, changedOrderIds, changedUserIds);
    }

    public static void pruneTerminalState(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          TerminalPruneBatch batch) {
        RuntimeOrderCommitStateTransitions.pruneTerminalState(runtime, identities, batch);
    }

    public static void replaceRiskScan(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                       CoreRiskState.RiskScan scan) {
        RuntimeRiskStateTransitions.replaceScan(runtime, identities, scan);
    }

    public static void replaceRiskScan(TradingRuntimeState runtime, RiskScanRuntime scan) {
        RuntimeRiskStateTransitions.replaceScan(runtime, scan);
    }

    public static void updateCancelAllAfter(TradingRuntimeState runtime, long userId,
                                            com.surprising.aeron.protocol.CoreCancelAllAfterCommand command) {
        RuntimeCancelAllAfterStateTransitions.update(runtime, userId, command);
    }

    public static void upsertAlgoOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                       long userId, CoreAlgoOrderView view) {
        RuntimeAlgoOrderStateTransitions.upsert(runtime, identities, userId, view);
    }

    public static void upsertAlgoOrder(TradingRuntimeState runtime, long userId,
                                       CoreAlgoOrderView view, int symbolId) {
        RuntimeAlgoOrderStateTransitions.upsert(runtime, userId, view, symbolId);
    }

    public static void upsertTriggerOrder(TradingRuntimeState runtime, RuntimeIdentityRegistry identities,
                                          long userId, CoreTriggerOrderStateView view) {
        RuntimeTriggerOrderStateTransitions.upsert(runtime, identities, userId, view);
    }

    public static void upsertTriggerOrder(TradingRuntimeState runtime, long userId,
                                          CoreTriggerOrderStateView view, int symbolId,
                                          long positionKey, boolean instrumentSettled) {
        RuntimeTriggerOrderStateTransitions.upsert(runtime, userId, view, symbolId, positionKey, instrumentSettled);
    }

    public static boolean cancelTriggerOrder(TradingRuntimeState runtime, long userId, long triggerOrderId) {
        return RuntimeTriggerOrderStateTransitions.cancel(runtime, userId, triggerOrderId);
    }

    public static boolean claimTriggerOrder(TradingRuntimeState runtime, long triggerOrderId, long triggerSequence,
                                            long triggeredPriceTicks, long triggeredAtEpochMillis) {
        return RuntimeTriggerOrderStateTransitions.claim(runtime, triggerOrderId, triggerSequence,
                triggeredPriceTicks, triggeredAtEpochMillis);
    }

    public static boolean completeTriggerOrder(TradingRuntimeState runtime, long triggerOrderId, boolean success,
                                               long placedOrderId, String rejectReason,
                                               long completedAtEpochMillis) {
        return RuntimeTriggerOrderStateTransitions.complete(runtime, triggerOrderId, success, placedOrderId,
                rejectReason, completedAtEpochMillis);
    }

    public static boolean updateTriggerTrailing(TradingRuntimeState runtime, long triggerOrderId,
                                                long highestPriceTicks, long lowestPriceTicks,
                                                long activatedAtEpochMillis) {
        return RuntimeTriggerOrderStateTransitions.updateTrailing(runtime, triggerOrderId, highestPriceTicks,
                lowestPriceTicks, activatedAtEpochMillis);
    }

    public static boolean expireTriggerOrder(TradingRuntimeState runtime, long triggerOrderId,
                                             long expiredAtEpochMillis) {
        return RuntimeTriggerOrderStateTransitions.expire(runtime, triggerOrderId, expiredAtEpochMillis);
    }

    public static boolean retryTriggerOrder(TradingRuntimeState runtime, long triggerOrderId,
                                            long staleBeforeEpochMillis, long retryAtEpochMillis) {
        return RuntimeTriggerOrderStateTransitions.retry(runtime, triggerOrderId, staleBeforeEpochMillis,
                retryAtEpochMillis);
    }

    public static long triggerPositionKey(RuntimeIdentityRegistry identities, ProductLine productLine,
                                          long userId, CoreTriggerOrderStateView view) {
        return RuntimeTriggerOrderStateTransitions.positionKey(identities, productLine, userId, view);
    }

    /** Owner builds the terminal state; settlement writes it on the account Lane. */
    public static CoreTriggerOrderState prepareMatchedTriggerCompletion(
            CoreTriggerOrderState current, long placedOrderId, long updatedAt) {
        return RuntimeTriggerOrderStateTransitions.prepareMatchedCompletion(current, placedOrderId, updatedAt);
    }

    public static CoreTriggerOrderState prepareRejectedTriggerCompletion(
            CoreTriggerOrderState current, String reason, long updatedAt) {
        return RuntimeTriggerOrderStateTransitions.prepareRejectedCompletion(current, reason, updatedAt);
    }
}
