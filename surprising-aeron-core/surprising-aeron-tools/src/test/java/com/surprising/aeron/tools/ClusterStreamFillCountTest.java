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

    private static CoreOrderBatchResult batch(CoreOrderBatchResult.Item... items) {
        return TradingOrderBatchCodec.decodeResult(TradingOrderBatchCodec.encodeResult(
                new CoreOrderBatchResult(List.of(items))));
    }

    private static CoreOrderBatchResult.Item item(int index, long id, long executed, ResponseStatus status) {
        return new CoreOrderBatchResult.Item(index, id, 0, 0, status, CoreResultCode.NONE,
                order(id, executed), executed == 0 ? List.of()
                        : List.of(new CoreExecutionView(id, 99, 1, 2, 100, 1)));
    }

    @Test void batchCountsActualExecutionsAcrossDecodedItems() {
        var result = batch(item(0, 10, 0, ResponseStatus.APPLIED), item(1, 11, 1, ResponseStatus.APPLIED));
        assertThat(ClusterCapacityMain.batchStreamFillCount(result, 10, 2)).isEqualTo(1);
    }

    @Test void fullyFilledBatchItemMayOmitRetiredOrderViewButMustHaveMatchingExecution() {
        var filled = new CoreOrderBatchResult.Item(0, 10, 0, 0, ResponseStatus.APPLIED,
                CoreResultCode.NONE, null, List.of(new CoreExecutionView(10, 99, 1, 2, 100, 1)));
        assertThat(ClusterCapacityMain.batchStreamFillCount(batch(filled), 10, 1)).isEqualTo(1);
        var missing = new CoreOrderBatchResult.Item(0, 10, 0, 0, ResponseStatus.APPLIED,
                CoreResultCode.NONE, null, List.of());
        assertThatThrownBy(() -> ClusterCapacityMain.batchStreamFillCount(batch(missing), 10, 1))
                .isInstanceOf(IllegalStateException.class);
        var mismatch = new CoreOrderBatchResult.Item(0, 10, 0, 0, ResponseStatus.APPLIED,
                CoreResultCode.NONE, null, List.of(new CoreExecutionView(11, 99, 1, 2, 100, 1)));
        assertThatThrownBy(() -> ClusterCapacityMain.batchStreamFillCount(batch(mismatch), 10, 1))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test void batchRejectsWrongCountIdentityAndRejectedItem() {
        var result = batch(item(0, 10, 1, ResponseStatus.APPLIED));
        assertThatThrownBy(() -> ClusterCapacityMain.batchStreamFillCount(result, 10, 2))
                .isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> ClusterCapacityMain.batchStreamFillCount(result, 11, 1))
                .isInstanceOf(IllegalStateException.class);
        var rejected = batch(item(0, 10, 0, ResponseStatus.REJECTED));
        assertThatThrownBy(() -> ClusterCapacityMain.batchStreamFillCount(rejected, 10, 1))
                .isInstanceOf(IllegalStateException.class);
    }
}
