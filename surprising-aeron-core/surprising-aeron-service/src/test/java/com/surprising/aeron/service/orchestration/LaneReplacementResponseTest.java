package com.surprising.aeron.service.orchestration;

import com.surprising.aeron.protocol.*;
import com.surprising.aeron.service.matching.CoreMatchingResult;
import com.surprising.aeron.service.state.OrderRuntime;
import com.surprising.aeron.service.state.instrument.CoreInstrument;
import com.surprising.instrument.api.model.ContractType;
import com.surprising.product.api.ProductLine;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class LaneReplacementResponseTest {
    private static final CoreInstrument INSTRUMENT = CoreInstrument.from(ProductLine.LINEAR_PERPETUAL,
            new RegisterInstrumentCommand("1", ContractType.LINEAR_PERPETUAL.ordinal(), "BTC", "USDT", "USDT",
                    1, 1, 1, 100_000, 50_000, 0, 0, 0, -1, 0));

    private static OrderRuntime order(long id, boolean canceled) {
        return new OrderRuntime(id, 7, 0, INSTRUMENT, CoreOrderSide.BUY, 0,
                false, CoreMarginMode.CROSS, CorePositionSide.NET, CoreOrderType.LIMIT,
                CoreTimeInForce.GTC, 0, 0, 10, 0, 10, canceled);
    }

    @Test void accountLaneEncodesBothOrderImagesAndReusesTargetAcrossSingleAndDoubleResults() {
        var target = new CommandSlot.LaneResultTarget();
        var arena = new ResponseArena(8, 4096);
        byte[] first = null;
        for (int generation = 0; generation < 64; generation++) {
            long id = generation * 2L + 1;
            target.bind(id, id + 1, arena);
            target.resultOrder(0, order(id, true), "1");
            target.resultOrder(1, order(id + 1, false), "1");
            target.matcherResult(new CoreMatchingResult(true, "SUCCESS"));
            target.prepareResponse();
            assertThat(target.preparedResponse()).isNotNull();
            byte[] response = Arrays.copyOf(target.preparedResponse(), target.preparedResponseLength());
            var view = CoreCommandResultCodec.decode(response);
            assertThat(view.orders()).extracting(CoreOrderStateView::orderId).containsExactly(id, id + 1);
            if (first == null) first = response;
            target.clear();

            target.bind(id, 0, arena);
            target.resultOrder(0, order(id, false), "1");
            target.matcherResult(new CoreMatchingResult(true, "SUCCESS"));
            target.prepareResponse();
            view = CoreCommandResultCodec.decode(Arrays.copyOf(target.preparedResponse(), target.preparedResponseLength()));
            assertThat(view.orders()).extracting(CoreOrderStateView::orderId).containsExactly(id);
            target.clear();
        }
        assertThat(CoreCommandResultCodec.decode(first).orders()).extracting(CoreOrderStateView::orderId)
                .containsExactly(1L, 2L);
    }
}
