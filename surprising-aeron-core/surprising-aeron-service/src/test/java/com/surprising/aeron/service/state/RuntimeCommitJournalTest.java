package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeCommitJournalTest {

    @Test
    void passiveJournalActivatesWithoutStartingAProjector() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = RuntimeCommitJournal.passive(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0, 0)) {
            assertThat(journal.activated()).isFalse();
            journal.activate();
            assertThat(journal.activated()).isTrue();
            assertThat(journal.projectorAlive()).isFalse();
            assertThat(journal.lag()).isZero();
            assertThat(journal.metrics().currentBacklog()).isZero();
        }
    }

    @Test
    void admissionTracksOnlyTheCurrentOwnerTransaction() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0)) {
            RuntimeCommitJournal.AdmissionReservation reservation = journal.reserveAdmission(2, 800);
            assertThat(journal.metrics().reservedEntries()).isEqualTo(2);
            assertThat(journal.metrics().reservedBytes()).isEqualTo(800);

            journal.release(reservation);

            assertThat(journal.metrics().reservedEntries()).isZero();
            assertThat(journal.metrics().reservedBytes()).isZero();
            assertThat(journal.metrics().currentBacklog()).isZero();
        }
    }

    @Test
    void metadataJournalCannotServeAsAStateReplica() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0, 7)) {
            assertThat(journal.current().sequence()).isEqualTo(7);
            assertThat(journal.current().state()).isNull();
            assertThatThrownBy(() -> journal.await(
                    7, System.nanoTime() + 1_000_000, true))
                    .isInstanceOf(UnsupportedOperationException.class)
                    .hasMessageContaining("materialize authoritative runtime");
        }
    }
}
