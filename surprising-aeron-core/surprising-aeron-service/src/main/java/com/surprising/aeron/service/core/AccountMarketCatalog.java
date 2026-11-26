package com.surprising.aeron.service.core;

import com.surprising.aeron.exception.CoreStateRejectedException;
import com.surprising.aeron.model.CoreFeePolicyState;
import com.surprising.aeron.protocol.CoreInstrumentMaintenance;
import com.surprising.aeron.protocol.CoreRiskScanControlView;
import com.surprising.aeron.protocol.TransferFundsCommand;
import com.surprising.aeron.protocol.UpdateRiskScanControlCommand;
import com.surprising.aeron.protocol.UpsertFeePolicyCommand;
import com.surprising.aeron.service.account.AccountPartition;
import com.surprising.aeron.service.fee.FeePolicyBook;
import com.surprising.aeron.service.instrument.CoreInstrument;
import com.surprising.aeron.service.market.MarkPrice;
import com.surprising.aeron.service.risk.RiskScanner;
import com.surprising.aeron.service.snapshot.state.TradingCoreState;
import com.surprising.aeron.service.treasury.SystemFunds;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;

/** 标的注册、标记价、风控扫描、待完成划转、费率政策与辅助状态替换协作器。 */
final class AccountMarketCatalog {
    private final AccountBook book;

    AccountMarketCatalog(AccountBook book) {
        this.book = book;
    }

    void registerInstrument(CoreInstrument instrument) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("instrument registry");
        if (instrument == null || book.instruments.containsKey(instrument.instrumentIdValue())) {
            throw new CoreStateRejectedException("INVALID_COMMAND", "instrument is already registered");
        }
        book.instruments.put(instrument.instrumentIdValue(), instrument);
    }

    void updateInstrumentConfiguration(CoreInstrument instrument, CoreInstrument updated) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("instrument configuration");
        if (instrument == null || updated == null || book.instruments.get(instrument.instrumentIdValue()) != instrument
                || instrument.instrumentIdValue() != updated.instrumentIdValue()) {
            throw new IllegalArgumentException("invalid canonical instrument configuration update");
        }
        instrument.updateConfiguration(updated);
    }

    void updateInstrumentMaintenance(CoreInstrument instrument, CoreInstrumentMaintenance maintenance) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("instrument operation");
        if (instrument == null || maintenance == null || book.instruments.get(instrument.instrumentIdValue()) != instrument) {
            throw new IllegalArgumentException("invalid canonical instrument operation");
        }
        instrument.updateMaintenance(maintenance);
    }

    Map<String, CoreInstrument> instrumentsForRuntime() {
        book.assertOwner();
        Map<String, CoreInstrument> exported = new TreeMap<>();
        book.instruments.forEachValue(instrument -> exported.put(instrument.instrumentId(), instrument));
        return exported;
    }

    RiskScanner.State firstIncompleteRiskScan() {
        book.assertOwner();
        RiskScanner.State selected = null;
        for (RiskScanner.State scan : book.riskScans.values()) {
            if (!scan.complete() && (selected == null || scan.symbolId() < selected.symbolId())) {
                selected = scan;
            }
        }
        return selected;
    }

    RiskScanner.State firstRiskIncompleteScan() {
        book.assertOwner();
        RiskScanner.State selected = null;
        for (RiskScanner.State scan : book.riskScans.values()) {
            if (!scan.riskComplete() && (selected == null || compareRiskProgress(scan, selected) < 0)) {
                selected = scan;
            }
        }
        return selected;
    }

    static int compareRiskProgress(RiskScanner.State left, RiskScanner.State right) {
        int scheduled = Long.compare(left.lastScheduledRevision(), right.lastScheduledRevision());
        return scheduled != 0 ? scheduled : Integer.compare(left.symbolId(), right.symbolId());
    }

    int incompleteRiskScanCount() {
        book.assertOwner();
        int count = 0;
        for (RiskScanner.State scan : book.riskScans.values()) {
            if (!scan.complete()) {
                count++;
            }
        }
        return count;
    }

    void putMarkPrice(MarkPrice markPrice) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("mark-price state");
        book.markPrices.put(markPrice.symbolId(), markPrice);
        book.changedMarkPrices.add(markPrice.symbolId());
    }

    void putRiskScan(RiskScanner.State scan) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("risk-scan state");
        book.riskScans.put(scan.symbolId(), scan);
        book.changedRiskScans.add(scan.symbolId());
    }

    void updateRiskScanControl(UpdateRiskScanControlCommand command, long updatedAtEpochMillis) {
        if (command == null) {
            throw new IllegalArgumentException("invalid runtime risk scan control update");
        }
        book.assertOwner();
        CoreRiskScanControlView current = book.riskScanControl();
        if (command.expectedVersion() != current.version()) {
            throw new CoreStateRejectedException("STALE_RISK_SCAN_CONTROL_VERSION",
                    "risk scan control version does not match");
        }
        book.requireRevisionCapacity();
        book.setRiskScanControl(new CoreRiskScanControlView(
                Math.incrementExact(current.version()), command.ruleName(), command.enabled(),
                command.scanDelayMs(), command.scanBatchSize(), command.adminUserId(), command.reason(),
                Math.max(0, updatedAtEpochMillis)));
        book.incrementCommandRevision();
    }

    void replaceRiskScan(RiskScanner.State scan) {
        if (scan == null) {
            throw new IllegalArgumentException("invalid runtime risk scan");
        }
        putRiskScan(scan);
        book.incrementCommandRevision();
    }

    long pendingTransferUser(long transferId) {
        book.assertOwner();
        if (book.currentLaneCommandScope() != null) throw new IllegalStateException("transfer routing belongs to Owner");
        return book.systemFunds.pendingTransferUser(transferId);
    }

    TransferFundsCommand pendingTransfer(long transferId) {
        book.assertOwner();
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane != null) return lane.pendingTransfer(transferId);
        if (book.accountLanesStarted()) throw new IllegalStateException("active transfer identity must be read on its Account Lane");
        long userId = pendingTransferUser(transferId);
        return userId == 0 ? null : book.accountLanes[book.topology.accountLaneId(userId)].pendingTransfer(transferId);
    }

    Map<Long, TransferFundsCommand> pendingTransfersSnapshot() {
        book.assertOwner();
        if (book.accountLanesStarted() && !book.ownerLaneAccess()) {
            throw new IllegalStateException("pending transfers snapshot requires owner lane access");
        }
        TreeMap<Long, TransferFundsCommand> values = new TreeMap<>();
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLaneReadOnly(laneId, lane -> { lane.forEachPendingTransfer(values::put); return null; });
        }
        return Collections.unmodifiableMap(values);
    }

    SystemFunds.PendingTransferRoutes pendingTransferRoutes(int limit) {
        book.assertOwner();
        return book.systemFunds.pendingTransferRoutes(limit, book.topology::accountLaneMask);
    }

    void completePendingTransferPage(SystemFunds.PendingTransferRoutes page) {
        book.assertOwner();
        book.systemFunds.completePendingTransferPage(page.ids().length == 0 ? 0 : page.ids()[page.ids().length - 1]);
    }

    void restorePendingTransfers(Map<Long, TransferFundsCommand> restored) {
        book.assertOwner();
        if (book.accountLanesStarted()) throw new IllegalStateException("transfer restore requires stopped Account Lanes");
        if (restored == null || restored.size() > SystemFunds.MAX_PENDING_TRANSFERS
                || restored.entrySet().stream().anyMatch(entry -> entry.getKey() == null
                || entry.getValue() == null || entry.getKey() != entry.getValue().transferId()))
            throw new IllegalArgumentException("invalid pending transfer snapshot");
        book.systemFunds.clearPendingTransfers();
        for (AccountPartition lane : book.accountLanes) lane.clearPendingTransferValues();
        restored.forEach((id, transfer) -> {
            book.systemFunds.restorePendingTransferRoute(id, transfer.sourceUserId());
            book.accountLanes[book.topology.accountLaneId(transfer.sourceUserId())].storePendingTransfer(id, transfer);
        });
    }

    boolean hasPendingTransferCapacity() {
        book.assertOwner();
        return book.systemFunds.hasPendingTransferCapacity();
    }

    void registerPendingTransferRoute(long id, long userId) {
        book.assertOwner();
        book.systemFunds.registerPendingTransferRoute(id, userId);
    }

    void completePendingTransferRoute(long id, long userId) {
        book.assertOwner();
        book.systemFunds.completePendingTransferRoute(id, userId);
    }

    void putPendingTransfer(TransferFundsCommand transfer) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("pending-transfer state");
        if (transfer == null) throw new IllegalArgumentException("pending transfer is required");
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) {
            if (book.accountLanesStarted()) throw new IllegalStateException("active transfer writes require their Account Lane");
            if (!hasPendingTransferCapacity())
                throw new CoreStateRejectedException("PENDING_TRANSFER_CAPACITY_FULL", "pending transfer runtime capacity is full");
            lane = book.accountLanes[book.topology.accountLaneId(transfer.sourceUserId())];
        }
        if (lane.laneId() != book.topology.accountLaneId(transfer.sourceUserId()) || lane.hasPendingTransfer(transfer.transferId()))
            throw new IllegalArgumentException("pending transfer already exists or reached the wrong Lane");
        lane.storePendingTransfer(transfer.transferId(), transfer);
        if (!book.accountLanesStarted()) registerPendingTransferRoute(transfer.transferId(), transfer.sourceUserId());
    }

    boolean removePendingTransfer(long transferId, long userId) {
        book.assertOwner();
        book.rejectUnsupportedOrderBatchMutation("pending-transfer state");
        AccountPartition lane = book.currentLaneCommandScope();
        if (lane == null) {
            if (book.accountLanesStarted()) throw new IllegalStateException("active transfer completion requires its Account Lane");
            long routed = pendingTransferUser(transferId);
            if (routed == 0) return false;
            if (routed != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
            lane = book.accountLanes[book.topology.accountLaneId(userId)];
        }
        if (lane.laneId() != book.topology.accountLaneId(userId)) throw new IllegalStateException("transfer completion crossed Account Lane");
        TransferFundsCommand current = lane.pendingTransfer(transferId);
        if (current == null) return false;
        if (current.sourceUserId() != userId) throw new CoreStateRejectedException("IDEMPOTENCY_CONFLICT", "transfer belongs to another user");
        book.requireRevisionCapacity();
        lane.removePendingTransferValue(transferId);
        if (!book.accountLanesStarted()) completePendingTransferRoute(transferId, userId);
        return true;
    }

    FeePolicyBook feePolicyBook() {
        book.assertOwner();
        return book.feePolicies;
    }

    Map<Long, CoreFeePolicyState> feePoliciesSnapshot() {
        book.assertOwner();
        return book.feePolicies.snapshot();
    }

    void restoreFeePolicies(Map<Long, CoreFeePolicyState> restored) {
        book.assertOwner();
        if (book.accountLanesStarted()) throw new IllegalStateException("fee policy restore requires stopped Lanes");
        book.feePolicies = FeePolicyBook.from(restored);
        book.changedFeePolicies.clear();
    }

    void upsertFeePolicy(UpsertFeePolicyCommand command) {
        book.assertOwner();
        if (book.accountLanesStarted() && (book.currentLaneCommandScope() == null || book.currentLaneCommandScope().laneId() != 0))
            throw new IllegalStateException("active fee policy updates must run on configuration Lane 0");
        book.rejectUnsupportedOrderBatchMutation("fee-policy state");
        CoreFeePolicyState next = CoreFeePolicyState.from(command);
        FeePolicyBook updated = book.feePolicies.upsert(next);
        if (updated == book.feePolicies) return;
        book.requireRevisionCapacity();
        book.feePolicies = updated;
        book.changedFeePolicies.add(next.policyId());
        book.incrementCommandRevision();
    }

    void replaceAuxiliaryState(TradingCoreState source) {
        book.assertOwner();
        book.setMetadata(source.productLine(), source.revision());
        book.setRiskScanControl(source.riskState().scanControl());
        book.instruments.clear();
        source.instruments().values().forEach(instrument -> book.instruments.put(instrument.instrumentIdValue(), instrument));
        for (int laneId = 0; laneId < book.accountLanes.length; laneId++) {
            book.onLane(laneId, lane -> {
                lane.clearLeverageValues();
                lane.clearLeverageKeys();
                lane.clearAlgoOrderValues();
                lane.clearTriggerOrderValues();
                lane.clearTriggerIds();
                return null;
            });
        }
        source.leverages().forEach(book::putLeverage);
        source.algoOrders().values().forEach(book::putAlgoOrder);
        book.cancelAllAfterTimers.clear();
        book.cancelAllAfterTimers.putAll(source.cancelAllAfterTimers());
        source.triggerOrders().values().forEach(book::putTriggerOrder);
    }
}
