package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeCommitJournalTest {

    @Test
    void passiveJournalActivates() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = RuntimeCommitJournal.passive(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0, 0)) {
            assertThat(journal.activated()).isFalse();
            journal.activate();
            assertThat(journal.activated()).isTrue();
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
        }
    }

    @Test
    void restoresItsPublicationSequence() {
        TradingCoreState initial = TradingCoreState.empty(ProductLine.SPOT);
        try (RuntimeCommitJournal journal = new RuntimeCommitJournal(
                ProductLine.SPOT, initial, initial.businessStateHash(), 0, 7)) {
            assertThat(journal.publishedSequence()).isEqualTo(7);
        }
    }
}
