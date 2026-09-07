package com.surprising.aeron.tools;

import com.surprising.aeron.protocol.*;
import com.surprising.product.api.ProductLine;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class ClusterStreamFillCountTest {
    private static CoreOrderStateView order(long id, long executed) {
        return new CoreOrderStateView(id, ProductLine.LINEAR_PERPETUAL, id, "STREAM1", 1,
                CoreOrderSide.BUY, 100, 1, executed, 1-executed, false,
                executed == 0 ? "OPEN" : "FILLED", 1);
    }
    private static CoreCommandResultView result(CoreOrderStateView... orders) {
        var result = new CoreCommandResultView(1, new UUID(0, 10), 10, 1, 1, 11, 12,
                List.of(orders), List.of());
        return CoreCommandResultCodec.decode(CoreCommandResultCodec.encode(result));
    }
    @Test void emptyExecutionDetailsStillCountTheNewOrdersActualFillOnce() {
        var response = result(order(9, 1), order(10, 1));
        assertThat(response.executions()).isEmpty();
        assertThat(ClusterCapacityMain.streamFillCount(response, 10)).isEqualTo(1);
    }
    @Test void restingOrderDoesNotCountAsAFill() {
        assertThat(ClusterCapacityMain.streamFillCount(result(order(10, 0)), 10)).isZero();
    }
    @Test void missingOrWrongOrderIdentityCannotBeReportedAsZeroFills() {
        assertThatThrownBy(() -> ClusterCapacityMain.streamFillCount(result(order(9, 1)), 10))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClusterCapacityMain.streamFillCount(result(order(10, 1)), 11))
                .isInstanceOf(IllegalStateException.class);
    }
}
