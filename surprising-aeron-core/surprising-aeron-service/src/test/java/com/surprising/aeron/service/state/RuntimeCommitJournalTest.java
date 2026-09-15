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
    void publishesOnlyTheNextSequenceWithoutAReservation() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0)) {
            assertThat(journal.publish(1, 101, 202)).isEqualTo(1);
            assertThatThrownBy(() -> journal.publish(3, 303, 404))
                    .isInstanceOf(IllegalStateException.class);
            assertThat(journal.publishedSequence()).isEqualTo(1);
            assertThat(journal.auditBusinessStateHash()).isEqualTo(101);
            assertThat(journal.auditFundsStateHash()).isEqualTo(202);
            assertThat(journal.publish(2, 303, 404)).isEqualTo(2);
            assertThat(journal.metrics().reservedEntries()).isZero();
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
