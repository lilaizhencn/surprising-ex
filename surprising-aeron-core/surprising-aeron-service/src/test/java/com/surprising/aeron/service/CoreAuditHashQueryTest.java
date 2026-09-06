package com.surprising.aeron.service;

import static org.assertj.core.api.Assertions.assertThat;
import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class CoreAuditHashQueryTest {
    @Test
    void explicitGlobalHashObservesCommittedFundsAndSurvivesSnapshot() {
        try (CoreProbeState state = new CoreProbeState(ProductLine.SPOT)) {
            long before = hash(state, CoreMessageType.BUSINESS_STATE_HASH_QUERY);
            CoreMessage adjust = new CoreMessage(CoreMessageHeader.command(CoreMessageType.ADJUST_BALANCE,
                    UUID.randomUUID(), ProductLine.SPOT, CommandSource.OPERATIONS, 77, 1, 11, 1, 1),
                    TradingCommandCodec.encodeBalanceAdjustment(new BalanceAdjustmentCommand("USDT", 123)));
            assertThat(state.apply(adjust).status()).isEqualTo(ResponseStatus.APPLIED);
            long after = hash(state, CoreMessageType.BUSINESS_STATE_HASH_QUERY);
            assertThat(after).isNotEqualTo(before).isEqualTo(state.tradingState().businessStateHash());
            assertThat(hash(state, CoreMessageType.STATE_HASH_QUERY)).isEqualTo(after);
            try (CoreProbeState restored = CoreProbeState.fromSnapshot(ProductLine.SPOT, state.snapshot(100))) {
                assertThat(hash(restored, CoreMessageType.BUSINESS_STATE_HASH_QUERY)).isEqualTo(after);
                assertThat(restored.apply(adjust).status()).isEqualTo(ResponseStatus.DUPLICATE);
                assertThat(hash(restored, CoreMessageType.STATE_HASH_QUERY)).isEqualTo(after);
            }
        }
    }

    private static long hash(CoreProbeState state, CoreMessageType type) {
        var response = state.apply(new CoreMessage(CoreMessageHeader.query(type, UUID.randomUUID(),
                ProductLine.SPOT, CommandSource.OPERATIONS, 77, 0, 0, 2, 2), new byte[0]));
        assertThat(response.status()).isEqualTo(ResponseStatus.OK);
        return response.stateHash();
    }
}
