package com.surprising.aeron.client;

import static org.assertj.core.api.Assertions.assertThat;

import io.aeron.Publication;
import org.junit.jupiter.api.Test;

class CoreCommandOutcomeTest {

    @Test
    void mapsEveryAeronNegativeOfferWithoutCollapsingTheRawValue() {
        assertReason(Publication.BACK_PRESSURED, CoreCommandOutcome.NotAcceptedReason.CLIENT_BACKPRESSURED);
        assertReason(Publication.NOT_CONNECTED, CoreCommandOutcome.NotAcceptedReason.NOT_CONNECTED);
        assertReason(Publication.ADMIN_ACTION, CoreCommandOutcome.NotAcceptedReason.ADMIN_ACTION);
        assertReason(Publication.CLOSED, CoreCommandOutcome.NotAcceptedReason.CLOSED);
        assertReason(Publication.MAX_POSITION_EXCEEDED,
                CoreCommandOutcome.NotAcceptedReason.MAX_POSITION_EXCEEDED);
        assertReason(-99, CoreCommandOutcome.NotAcceptedReason.UNKNOWN);
    }

    private static void assertReason(long raw, CoreCommandOutcome.NotAcceptedReason reason) {
        CoreCommandOutcome.NotAccepted result = CoreCommandOutcome.notAccepted(raw);
        assertThat(result.reason()).isEqualTo(reason);
        assertThat(result.rawOfferResult()).isEqualTo(raw);
    }
}
