package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.RuntimeCommitJournal;

/** Bounded owner-commit admission. Export/Core-Fact capacity is not part of the trading path. */
final class CoreAdmissionReservation {
    private RuntimeCommitJournal journal;
    private java.util.ArrayDeque<CoreAdmissionReservation> recycler;
    private int remainingFrames;
    private int holders = 1;
    private boolean released;

    private CoreAdmissionReservation() {
    }

    static CoreAdmissionReservation reserve(RuntimeCommitJournal journal, CoreExportState ignored,
                                            AdmissionDemand demand) {
        return reserve(journal, ignored, demand, null);
    }

    static CoreAdmissionReservation reserve(RuntimeCommitJournal journal, CoreExportState ignored,
                                            AdmissionDemand demand,
                                            java.util.ArrayDeque<CoreAdmissionReservation> recycler) {
        journal.assertHealthy();
        journal.reserveEntries(demand.patchCount());
        CoreAdmissionReservation reservation = recycler == null ? new CoreAdmissionReservation()
                : recycler.pollFirst();
        if (reservation == null) reservation = new CoreAdmissionReservation();
        reservation.journal = journal;
        reservation.recycler = recycler;
        reservation.remainingFrames = demand.patchCount();
        reservation.holders = 1;
        reservation.released = false;
        return reservation;
    }

    long publish(long sequence) {
        requireOpen();
        if (remainingFrames == 0) throw new IllegalStateException("commit watermark budget exhausted");
        long published = journal.publishReserved(sequence);
        remainingFrames--;
        return published;
    }

    void releaseUnused() {
        if (released) return;
        if (--holders > 0) return;
        if (remainingFrames > 0) journal.releaseEntries(remainingFrames);
        remainingFrames = 0;
        released = true;
        java.util.ArrayDeque<CoreAdmissionReservation> target = recycler;
        journal = null;
        recycler = null;
        if (target != null) target.addFirst(this);
    }

    int remainingFrames() { return remainingFrames; }
    int holders() { return holders; }

    void retainHolders(int additionalHolders) {
        requireOpen();
        if (additionalHolders < 1) throw new IllegalArgumentException("additional holders must be positive");
        holders = Math.addExact(holders, additionalHolders);
    }

    private void requireOpen() {
        if (released) throw new IllegalStateException("core admission reservation is released");
    }

    record AdmissionDemand(int patchCount) {
        private static final AdmissionDemand ONE = new AdmissionDemand(1);
        private static final AdmissionDemand TWO = new AdmissionDemand(2);
        private static final AdmissionDemand THREE = new AdmissionDemand(3);
        AdmissionDemand {
            if (patchCount < 1) {
                throw new IllegalArgumentException("admission demand must be positive");
            }
        }

        static AdmissionDemand direct(CoreMessage message, int ignoredRiskScanUserBound) {
            return forOperations(operationCount(message.header().messageType()));
        }

        static AdmissionDemand matching(CoreMessage message) {
            return matching(message, 0);
        }

        static AdmissionDemand matching(CoreMessage message, int ignoredMatchingOrderBound) {
            return forOperations(operationCount(message.header().messageType()));
        }

        static AdmissionDemand matching(CoreMessage message, int ignoredMatchingOrderBound,
                                        DecodedMatchingCommand ignoredDecodedCommand) {
            return forOperations(operationCount(message.header().messageType()));
        }

        private static AdmissionDemand forOperations(int operations) {
            return switch (operations) {
                case 1 -> ONE;
                case 2 -> TWO;
                case 3 -> THREE;
                default -> new AdmissionDemand(operations);
            };
        }

        private static int operationCount(CoreMessageType type) {
            return switch (type) {
                case APPLY_FUNDING, RESOLVE_LIQUIDATION, CONTINUE_RISK_SCAN, EXECUTE_ADL,
                     EXECUTE_LIQUIDATION, EXECUTE_LIQUIDATION_BATCH, SETTLE_INSTRUMENT -> 3;
                case EXECUTE_TRIGGER_ORDER -> 2;
                default -> 1;
            };
        }
    }
}
