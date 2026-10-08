package com.surprising.trading.api.model;

import static org.assertj.core.api.Assertions.*;
import com.surprising.product.api.ProductLine;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MakerQuoteInputsTest {
    @Test void requestIsBoundedAndOwnsItsAccountList() {
        var users = new ArrayList<>(List.of(1L, 2L));
        var request = new MakerQuoteInputs.Request(ProductLine.SPOT, "1", 7, MarginMode.CROSS, users);
        users.clear();
        assertThat(request.accountIds()).containsExactly(1L, 2L);
        for (var invalid : List.of(List.of(1L, 1L), List.of(0L), List.<Long>of(),
                java.util.stream.LongStream.rangeClosed(1, 65).boxed().toList()))
            assertThatThrownBy(() -> new MakerQuoteInputs.Request(ProductLine.SPOT, "1", 7, MarginMode.CROSS, invalid))
                    .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new MakerQuoteInputs.Request(ProductLine.SPOT, "1", 0, MarginMode.CROSS, List.of(1L)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
