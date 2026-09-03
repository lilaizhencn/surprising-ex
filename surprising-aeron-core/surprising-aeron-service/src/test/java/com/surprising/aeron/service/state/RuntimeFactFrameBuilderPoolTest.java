package com.surprising.aeron.service.state;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

class RuntimeFactFrameBuilderPoolTest {

    @Test
    void preallocatesAndReusesReleasedBuilderWithoutRetainingFrameState() {
        RuntimeFactFrameBuilderPool pool = new RuntimeFactFrameBuilderPool(ProductLine.LINEAR_PERPETUAL, 1);
        RuntimeFactFrame.Builder first = pool.acquire();
        first.sequences(0, 1);

        assertThatThrownBy(pool::acquire)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("capacity is exhausted");

        pool.release(first);
        RuntimeFactFrame.Builder reused = pool.acquire();
        assertThat(reused).isSameAs(first);
        reused.sequences(0, 1);
    }
}
