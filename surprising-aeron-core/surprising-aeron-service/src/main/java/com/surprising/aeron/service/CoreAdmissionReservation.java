package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreProtocol;
import com.surprising.aeron.protocol.TradingCommandCodec;
import com.surprising.aeron.protocol.TradingOrderBatchCodec;
import com.surprising.aeron.service.state.RuntimeCommitJournal;
import com.surprising.aeron.service.state.RuntimeFactFrame;

final class CoreAdmissionReservation {
    @FunctionalInterface
    interface FactEstimateFaultInjector {
        FactCostEstimate inject(CoreMessage message, FactCostEstimate estimate);
    }

    private static volatile FactEstimateFaultInjector factEstimateFaultInjector = (message, estimate) -> estimate;
    private static volatile long factAdmissionByteLimit;

    static void setFactEstimateFaultInjectorForTest(FactEstimateFaultInjector injector) {
        factEstimateFaultInjector = injector == null ? (message, estimate) -> estimate : injector;
    }

    static void setFactAdmissionByteLimitForTest(long limit) {
        factAdmissionByteLimit = Math.max(0, limit);
    }

    private final RuntimeCommitJournal journal;
    private final CoreExportState exportState;
    private final CoreExportState.AdmissionReservation exportReservation;
    private final FactBudget factBudget;
    private int remainingFrames;
    private int holders = 1;
    private boolean released;

    private CoreAdmissionReservation(RuntimeCommitJournal journal, CoreExportState exportState,
                                     CoreExportState.AdmissionReservation exportReservation,
                                     FactBudget factBudget, int patchCount) {
        this.journal = journal;
        this.exportState = exportState;
        this.exportReservation = exportReservation;
        this.factBudget = factBudget;
        this.remainingFrames = patchCount;
    }

    static CoreAdmissionReservation reserve(RuntimeCommitJournal journal, CoreExportState exportState,
                                            AdmissionDemand demand) {
        journal.assertHealthy();
        CoreExportState.AdmissionReservation facts =
                exportState.reserveAdmission(demand.factCount(), demand.factBytes());
        return new CoreAdmissionReservation(journal, exportState, facts,
                new FactBudget(demand.factChainNodes(), demand.factItems(), demand.factByteUpperBound()),
                demand.patchCount());
    }

    long publish(com.surprising.aeron.service.state.TradingRuntimeState.PreparedFactFrame frame,
                 long businessStateHash, long fundsStateHash) {
        requireOpen();
        if (remainingFrames == 0) {
            throw new IllegalStateException("commit watermark budget exhausted");
        }
        long sequence = journal.publish(frame.sequence(), businessStateHash, fundsStateHash);
        remainingFrames--;
        return sequence;
    }

    long append(CoreExportState.Draft draft) {
        requireOpen();
        return exportState.append(exportReservation, draft);
    }

    FactPermit reserveFactFrame() {
        requireOpen();
        return factBudget.reserveFrame();
    }

    void abortFactFrame(FactPermit permit) {
        requireOpen();
        factBudget.abort(permit);
    }

    void releaseUnused() {
        if (released) return;
        if (--holders > 0) return;
        if (exportReservation.remainingEvents() > 0) exportState.release(exportReservation);
        factBudget.release();
        remainingFrames = 0;
        released = true;
    }

    int remainingFrames() { return remainingFrames; }
    int remainingFacts() { return exportReservation.remainingEvents(); }
    int holders() { return holders; }
    int remainingFactNodes() { return factBudget.remainingNodes(); }
    int remainingFactItems() { return factBudget.remainingItems(); }
    long remainingFactBytes() { return factBudget.remainingBytes(); }

    void retainHolders(int additionalHolders) {
        requireOpen();
        if (additionalHolders < 1) throw new IllegalArgumentException("additional holders must be positive");
        holders = Math.addExact(holders, additionalHolders);
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("core admission reservation is released");
    }

    record AdmissionDemand(int patchCount, long patchBytes, int factCount, long factBytes,
                           int factChainNodes, int factItems, long factByteUpperBound) {
        AdmissionDemand(int patchCount, long patchBytes, int factCount, long factBytes) {
            this(patchCount, patchBytes, factCount, factBytes, patchCount,
                    Math.multiplyExact(patchCount, 65_536),
                    Math.multiplyExact(patchCount, CoreExportState.maxReservedEventBytes()));
        }
        AdmissionDemand {
            if (patchCount < 1 || patchBytes < 1 || factCount < 1 || factBytes < 1) {
                throw new IllegalArgumentException("admission demand must be positive");
            }
            if (factChainNodes < patchCount || factItems < factChainNodes
                    || factByteUpperBound < factChainNodes) {
                throw new IllegalArgumentException("fact budget must cover every patch");
            }
        }

        static AdmissionDemand direct(CoreMessage message, int riskScanUserBound) {
            CoreMessageType type = message.header().messageType();
            int operations = switch (type) {
                case APPLY_FUNDING, RESOLVE_LIQUIDATION, CONTINUE_RISK_SCAN, EXECUTE_ADL -> 3;
                case EXECUTE_TRIGGER_ORDER -> 2;
                default -> 1;
            };
            return of(operations, operations, FactCostEstimate.from(message, operations, riskScanUserBound));
        }

        static AdmissionDemand matching(CoreMessage message) {
            return matching(message, 0);
        }

        static AdmissionDemand matching(CoreMessage message, int matchingOrderBound) {
            if (matchingOrderBound < 0) throw new IllegalArgumentException("matching order bound cannot be negative");
            CoreMessageType type = message.header().messageType();
            int operations = switch (type) {
                case EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH, SETTLE_INSTRUMENT -> 3;
                default -> 1;
            };
            return of(operations, operations,
                    FactCostEstimate.from(message, operations, 0, matchingOrderBound));
        }

        private static AdmissionDemand of(int patchCount, int factCount, FactCostEstimate estimate) {
            long patchBytesPerEntry = Math.min(
                    RuntimeCommitJournal.maxReservedPatchBytes(), estimate.bytes());
            return new AdmissionDemand(patchCount,
                    Math.multiplyExact(patchBytesPerEntry, patchCount),
                    factCount, estimate.bytes(),
                    estimate.nodes(), estimate.items(), estimate.bytes());
        }
    }

    record FactCostEstimate(int nodes, int items, long bytes) {
        private static final int BASE_ITEMS_PER_NODE = 32;

        FactCostEstimate {
            if (nodes < 1 || items < nodes || bytes < nodes
                    || bytes > CoreExportState.maxReservedAdmissionBytes(nodes)) {
                throw new com.surprising.aeron.service.state.CoreStateRejectedException(
                        "EXPORT_BACKLOG_FULL", "command worst-case Core Fact exceeds bounded admission capacity");
            }
        }

        static FactCostEstimate from(CoreMessage message, int nodes, int riskScanUserBound) {
            return from(message, nodes, riskScanUserBound, 0);
        }

        static FactCostEstimate from(CoreMessage message, int nodes, int riskScanUserBound,
                                     int matchingOrderBound) {
            int boundedWorkItems = switch (message.header().messageType()) {
                case PLACE_ORDER -> quantityBoundedItems(
                        TradingCommandCodec.decodePlaceOrder(message.payloadUnsafe()).quantitySteps(),
                        matchingOrderBound);
                case APPLY_FUNDING -> Math.multiplyExact(
                        TradingCommandCodec.decodeApplyFunding(message.payloadUnsafe()).maxUsers(), 10);
                case CONTINUE_RISK_SCAN -> Math.multiplyExact(
                        TradingCommandCodec.decodeContinueRiskScan(message.payloadUnsafe()).maxUsers(), 12);
                case APPLY_MARK_PRICE -> {
                    TradingCommandCodec.decodeApplyMarkPrice(message.payloadUnsafe());
                    if (riskScanUserBound < 1) throw new IllegalArgumentException("risk scan bound is required");
                    yield BASE_ITEMS_PER_NODE;
                }
                case EXECUTE_LIQUIDATION -> Math.multiplyExact(
                        TradingCommandCodec.decodeExecuteLiquidation(message.payloadUnsafe()).maxOrders(), 16);
                case EXECUTE_LIQUIDATION_BATCH -> {
                    var command = TradingCommandCodec.decodeExecuteLiquidationBatch(message.payloadUnsafe());
                    yield Math.addExact(Math.addExact(Math.multiplyExact(command.actions().size(), 24),
                                    Math.multiplyExact(command.maxCancelOrders(), 12)),
                            Math.multiplyExact(command.maxRiskScanUsers(), 12));
                }
                case SETTLE_INSTRUMENT -> {
                    var command = TradingCommandCodec.decodeSettleInstrument(message.payloadUnsafe());
                    yield Math.addExact(Math.multiplyExact(command.maxUsers(), 12),
                            Math.multiplyExact(command.maxOrders(), 16));
                }
                case PLACE_ORDER_BATCH -> placeOrderBatchBoundedItems(message, matchingOrderBound);
                case CANCEL_ORDER_BATCH -> Math.multiplyExact(
                        TradingOrderBatchCodec.decodeCancelOrderBatch(message.payloadUnsafe()).orders().size(), 16);
                case AMEND_ORDER_BATCH -> Math.multiplyExact(
                        TradingOrderBatchCodec.decodeAmendOrderBatch(message.payloadUnsafe()).orders().size(), 16);
                case EXECUTE_ADL -> {
                    TradingCommandCodec.decodeExecuteAdl(message.payloadUnsafe());
                    yield 128;
                }
                case RESOLVE_LIQUIDATION -> {
                    TradingCommandCodec.decodeResolveLiquidation(message.payloadUnsafe());
                    yield 128;
                }
                default -> 256;
            };
            int items = Math.addExact(Math.multiplyExact(nodes, BASE_ITEMS_PER_NODE), boundedWorkItems);
            long perEventBytes = Math.addExact(CoreProtocol.HEADER_LENGTH + 4_096L,
                    message.payloadLength());
            long bytes = Math.addExact(Math.multiplyExact(nodes, perEventBytes),
                    Math.multiplyExact((long) items, 2_048L));
            if (factAdmissionByteLimit > 0 && bytes > factAdmissionByteLimit) {
                throw new com.surprising.aeron.service.state.CoreStateRejectedException(
                        "EXPORT_BACKLOG_FULL", "command worst-case Core Fact exceeds admission capacity");
            }
            FactCostEstimate estimate = new FactCostEstimate(nodes, items, bytes);
            return java.util.Objects.requireNonNull(
                    factEstimateFaultInjector.inject(message, estimate), "fact estimate fault injector");
        }

        private static int placeOrderBatchBoundedItems(CoreMessage message, int matchingOrderBound) {
            int items = 0;
            for (var order : TradingOrderBatchCodec.decodePlaceOrderBatch(message.payloadUnsafe()).orders()) {
                items = addBoundedItems(items, quantityBoundedItems(order.quantitySteps(), matchingOrderBound));
            }
            return items;
        }

        private static int quantityBoundedItems(long quantitySteps, int matchingOrderBound) {
            try {
                long fillBound = Math.min(quantitySteps, Math.max(1, matchingOrderBound));
                return Math.toIntExact(Math.multiplyExact(fillBound, 16L));
            } catch (ArithmeticException failure) {
                throw admissionCapacityExceeded();
            }
        }

        private static int addBoundedItems(int current, int additional) {
            try {
                return Math.addExact(current, additional);
            } catch (ArithmeticException failure) {
                throw admissionCapacityExceeded();
            }
        }

        private static com.surprising.aeron.service.state.CoreStateRejectedException
        admissionCapacityExceeded() {
            return new com.surprising.aeron.service.state.CoreStateRejectedException(
                    "EXPORT_BACKLOG_FULL", "command worst-case Core Fact exceeds bounded admission capacity");
        }
    }

    static final class FactEstimateInvariantException extends IllegalStateException {
        FactEstimateInvariantException(String message) {
            super(message);
        }
    }

    static final class FactPermit {
        private final FactBudget owner;
        private final int ordinal;
        private final int maxItems;
        private final long maxBytes;
        private boolean consumed;
        private boolean returned;

        private FactPermit(FactBudget owner, int ordinal, int maxItems, long maxBytes) {
            this.owner = owner;
            this.ordinal = ordinal;
            this.maxItems = maxItems;
            this.maxBytes = maxBytes;
        }

        int ordinal() { return ordinal; }

        boolean sameOwner(FactPermit other) {
            return other != null && owner == other.owner;
        }

        void consume() {
            owner.consume(this);
        }

        /** Off-owner compatibility path. The trading owner uses consume() and never materializes here. */
        void consume(com.surprising.aeron.service.state.RuntimeFactFrame fact) {
            java.util.Objects.requireNonNull(fact, "fact");
            if (fact.coreFactItemCount() > maxItems || fact.estimatedCoreFactBytes() > maxBytes) {
                throw new IllegalStateException("fact exceeds pre-mutation fact bound");
            }
            owner.consume(this);
        }

        void requireConsumed() {
            owner.requireConsumed(this);
        }

        void returnUnused() {
            owner.returnUnused(this);
        }

    }

    static final class FactBudget {
        private int remainingNodes;
        private int remainingItems;
        private long remainingBytes;
        private int nextOrdinal = 1;
        private int nextConsumeOrdinal = 1;
        private boolean released;

        FactBudget(int nodes, int items, long bytes) {
            if (nodes < 1 || items < nodes || bytes < nodes) {
                throw new IllegalArgumentException("invalid fact budget");
            }
            remainingNodes = nodes;
            remainingItems = items;
            remainingBytes = bytes;
        }

        FactPermit reserveFrame() {
            if (released || remainingNodes == 0) {
                throw new IllegalStateException("fact chain node budget exhausted");
            }
            remainingNodes--;
            return new FactPermit(this, nextOrdinal++, remainingItems, remainingBytes);
        }

        private void consume(FactPermit permit) {
            requireOwnedActive(permit);
            if (permit.consumed || permit.returned) throw new IllegalStateException("fact permit already resolved");
            if (permit.ordinal != nextConsumeOrdinal) {
                throw new IllegalStateException("fact permit gap or reorder");
            }
            permit.consumed = true;
            nextConsumeOrdinal++;
        }

        private void returnUnused(FactPermit permit) {
            requireOwnedActive(permit);
            if (permit.consumed || permit.returned) throw new IllegalStateException("fact permit already resolved");
            permit.returned = true;
        }

        private void abort(FactPermit permit) {
            requireOwnedActive(permit);
            if (!permit.consumed && !permit.returned) permit.returned = true;
            release();
        }

        private void requireConsumed(FactPermit permit) {
            requireOwnedActive(permit);
            if (!permit.consumed || permit.returned) {
                throw new IllegalStateException("fact permit was not consumed exactly once");
            }
        }

        private void requireOwnedActive(FactPermit permit) {
            if (permit == null || permit.owner != this) {
                throw new IllegalStateException("foreign or stale fact permit");
            }
            if (released) throw new IllegalStateException("fact budget is released");
        }

        void release() {
            if (released) return;
            remainingNodes = 0;
            remainingItems = 0;
            remainingBytes = 0;
            released = true;
        }

        int remainingNodes() { return remainingNodes; }
        int remainingItems() { return remainingItems; }
        long remainingBytes() { return remainingBytes; }
    }
}
