package com.surprising.aeron.tools;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.surprising.product.api.ProductLine;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class W4LifecycleQaMainTest {

    @Test
    void declaresTheExactRowsForEveryRequiredProductLine() {
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.SPOT))
                .isEqualTo("SPOT:CONSERVATION,SPOT:CONTROL_GUARD,SPOT:TRADES_FEES");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.LINEAR_PERPETUAL))
                .isEqualTo("LINEAR_PERPETUAL:CROSS,LINEAR_PERPETUAL:ISOLATED,FUNDING_POSITIVE,FUNDING_NEGATIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL,TRADES_FEES");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.INVERSE_PERPETUAL))
                .isEqualTo("INVERSE_PERPETUAL:CROSS,INVERSE_PERPETUAL:ISOLATED,FUNDING_POSITIVE,FUNDING_NEGATIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL,TRADES_FEES");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.LINEAR_DELIVERY))
                .isEqualTo("LINEAR_DELIVERY:CROSS,LINEAR_DELIVERY:ISOLATED,SETTLEMENT,CURSOR,TRADES_FEES");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.INVERSE_DELIVERY))
                .isEqualTo("INVERSE_DELIVERY:CROSS,INVERSE_DELIVERY:ISOLATED,SETTLEMENT,CURSOR,TRADES_FEES");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.OPTION))
                .isEqualTo("OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM,OPTION:TRADES_FEES");
    }

    @Test
    void querySequenceAllocatorAdvancesAndStoresEveryValue() {
        W4LifecycleQaMain qa = new W4LifecycleQaMain(ProductLine.SPOT, null, 1);
        long initial = qa.sequence();

        assertThat(qa.nextSequence()).isEqualTo(initial + 1);
        assertThat(qa.sequence()).isEqualTo(initial + 1);
        assertThat(qa.nextSequence()).isEqualTo(initial + 2);
        assertThat(qa.sequence()).isEqualTo(initial + 2);
    }

    @Test
    void manifestCannotClaimFundsDifferenceBeforeObservedReconciliation() throws Exception {
        W4LifecycleQaMain qa = new W4LifecycleQaMain(ProductLine.SPOT, null, 1);
        Path manifest = Files.createTempFile("w4-manifest-", ".env");
        Files.delete(manifest);

        assertThatThrownBy(() -> qa.writeManifest(manifest, "execute"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("FUNDS_RECONCILIATION_REQUIRED");
    }

    @Test
    void requiredProductLinesStayOrdered() {
        assertThat(W4LifecycleQaMain.REQUIRED_PRODUCT_LINES)
                .containsExactly(ProductLine.SPOT, ProductLine.LINEAR_PERPETUAL,
                        ProductLine.INVERSE_PERPETUAL, ProductLine.LINEAR_DELIVERY,
                        ProductLine.INVERSE_DELIVERY, ProductLine.OPTION);
    }

    private static void restoreProperty(String name, String value) {
        if (value == null) {
            System.clearProperty(name);
        } else {
            System.setProperty(name, value);
        }
    }
}
