package com.surprising.aeron.service;

import com.surprising.aeron.protocol.CoreMessage;
import com.surprising.aeron.protocol.CoreMessageType;
import com.surprising.aeron.service.state.RuntimeCommitJournal;

/** Bounded owner-commit admission. Export/Core-Fact capacity is not part of the trading path. */
final class CoreAdmissionReservation {
    private final RuntimeCommitJournal journal;
    private final RuntimeCommitJournal.AdmissionReservation journalReservation;
    private int remainingFrames;
    private int holders = 1;
    private boolean released;

    private CoreAdmissionReservation(RuntimeCommitJournal journal,
                                     RuntimeCommitJournal.AdmissionReservation journalReservation,
                                     int patchCount) {
        this.journal = journal;
        this.journalReservation = journalReservation;
        this.remainingFrames = patchCount;
    }

    static CoreAdmissionReservation reserve(RuntimeCommitJournal journal, CoreExportState ignored,
                                            AdmissionDemand demand) {
        journal.assertHealthy();
        return new CoreAdmissionReservation(journal,
                journal.reserveAdmission(demand.patchCount(), demand.patchBytes()), demand.patchCount());
    }

    long publish(long sequence, long businessStateHash, long fundsStateHash) {
        requireOpen();
        if (remainingFrames == 0) throw new IllegalStateException("commit watermark budget exhausted");
        long published = journal.publish(journalReservation, sequence, businessStateHash, fundsStateHash);
        remainingFrames--;
        return published;
    }

    void releaseUnused() {
        if (released) return;
        if (--holders > 0) return;
        if (journalReservation.remaining() > 0) journal.release(journalReservation);
        remainingFrames = 0;
        released = true;
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

    record AdmissionDemand(int patchCount, long patchBytes) {
        AdmissionDemand {
            if (patchCount < 1 || patchBytes < 1) {
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
            return new AdmissionDemand(operations, Math.multiplyExact(64L, operations));
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
