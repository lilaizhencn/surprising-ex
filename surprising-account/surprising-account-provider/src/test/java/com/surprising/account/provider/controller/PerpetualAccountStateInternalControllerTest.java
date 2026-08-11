package com.surprising.account.provider.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.surprising.account.api.model.PerpetualAccountStateUpdatedEvent;
import com.surprising.account.provider.service.AccountUserStateCommandWorker;
import com.surprising.account.provider.service.AccountUserStateReducer;
import com.surprising.account.provider.service.PerpetualAccountStateSnapshotService;
import com.surprising.eventstore.UserPartitionKey;
import com.surprising.product.api.ProductLine;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class PerpetualAccountStateInternalControllerTest {

    @Test
    void recoveryPublishesTheCanonicalSnapshotForDownstreamConsumers() {
        PerpetualAccountStateSnapshotService snapshotService = mock(PerpetualAccountStateSnapshotService.class);
        AccountUserStateReducer stateReducer = mock(AccountUserStateReducer.class);
        AccountUserStateCommandWorker stateWorker = mock(AccountUserStateCommandWorker.class);
        PerpetualAccountStateUpdatedEvent snapshot = snapshot();
        when(stateReducer.snapshot(new UserPartitionKey(ProductLine.LINEAR_PERPETUAL, 900001L)))
                .thenReturn(Optional.of(snapshot));
        var controller = new PerpetualAccountStateInternalController(snapshotService, stateReducer, stateWorker);

        var response = controller.recover(ProductLine.LINEAR_PERPETUAL, 900001L);

        assertThat(response).isSameAs(snapshot);
        verify(stateWorker).publishStateSnapshotForRecovery(snapshot);
    }

    private PerpetualAccountStateUpdatedEvent snapshot() {
        return new PerpetualAccountStateUpdatedEvent(
                PerpetualAccountStateUpdatedEvent.CURRENT_SCHEMA_VERSION,
                17L,
                1L,
                ProductLine.LINEAR_PERPETUAL,
                900001L,
                "USDT_PERPETUAL",
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                List.of(),
                com.surprising.trading.api.model.PositionMode.ONE_WAY,
                Instant.parse("2026-08-11T00:00:00Z"),
                "test");
    }
}
