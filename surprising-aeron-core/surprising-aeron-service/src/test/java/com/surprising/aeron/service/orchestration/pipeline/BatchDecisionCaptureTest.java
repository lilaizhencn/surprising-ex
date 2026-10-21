package com.surprising.aeron.service.orchestration.pipeline;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.orchestration.TradingCoreRuntime;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.UUID;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.*;

class BatchDecisionCaptureTest {
    private static final long TIME = 1_700_000_000_000L;
    private long sequence;

    @Test void contextsAreSharedPerSymbolAndReleasedBeforeRecapturingANewPrefix() {
        try (var core = new TradingCoreRuntime(ProductLine.LINEAR_PERPETUAL)) {
            for (String symbol : new String[]{"1", "2"}) {
                applied(core, CoreMessageType.REGISTER_INSTRUMENT, TradingCommandCodec.encodeRegisterInstrument(
                        new RegisterInstrumentCommand(symbol, ContractType.LINEAR_PERPETUAL.ordinal(),
                                "BTC", "USDT", "USDT", 1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0)));
                mark(core, symbol, 100, 1);
            }
            var batch = new OrderBatchPending(2, core.responseArena());
            batch.clusterTimestamp = TIME;
            var first = core.batches().prepareBatchDecision(batch, 7, "1");
            var second = core.batches().prepareBatchDecision(batch, 7, "2");
            assertThat(core.batches().prepareBatchDecision(batch, 7, "1")).isSameAs(first);
            assertThat(second).isNotSameAs(first);
            assertThat(batch.preparedSymbols).containsExactly("1", "2");
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);

            // This standalone batch has not been submitted: a later configuration version
            // demonstrates that captured values survive until its explicit prefix boundary.
            mark(core, "1", 200, 2);
            assertThat(core.batches().prepareBatchDecision(batch, 7, "1")).isSameAs(first);
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);
            batch.clearPreparedDecisionCache();
            assertThat(batch.preparedSymbols).isEmpty();
            assertThat(batch.preparedContextDecisions).containsOnlyNulls();
            var next = core.batches().prepareBatchDecision(batch, 7, "1");
            assertThat(next).isNotSameAs(first);
            assertThat(next.context().mark().markPriceTicks()).isEqualTo(200);
            assertThat(first.context().mark().markPriceTicks()).isEqualTo(100);
            batch.clear();
            assertThat(batch.preparedSymbols).isEmpty();
            assertThat(batch.preparedContextDecisions).containsOnlyNulls();
        }
    }

    private void mark(TradingCoreRuntime core, String symbol, long price, long version) {
        applied(core, CoreMessageType.APPLY_MARK_PRICE, TradingCommandCodec.encodeApplyMarkPrice(
                new ApplyMarkPriceCommand(symbol, price, version, TIME)));
    }

    private void applied(TradingCoreRuntime core, CoreMessageType type, byte[] payload) {
        long id = ++sequence;
        var message = new CoreMessage(CoreMessageHeader.command(type, new UUID(950, id), core.productLine(),
                CommandSource.GATEWAY, 950, id, 0, TIME, id), payload);
        assertThat(CoreTestCompletion.applyAsynchronously(core, message).commandStatus()).isEqualTo(ResponseStatus.APPLIED);
    }
}
