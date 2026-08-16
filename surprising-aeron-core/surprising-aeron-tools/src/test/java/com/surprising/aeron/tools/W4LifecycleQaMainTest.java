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
                .isEqualTo("SPOT:CONSERVATION,SPOT:CONTROL_GUARD");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.LINEAR_PERPETUAL))
                .isEqualTo("LINEAR_PERPETUAL:CROSS,LINEAR_PERPETUAL:ISOLATED,FUNDING_POSITIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.INVERSE_PERPETUAL))
                .isEqualTo("INVERSE_PERPETUAL:CROSS,INVERSE_PERPETUAL:ISOLATED,FUNDING_POSITIVE,MARK,RISK_SCAN,LIQUIDATION,INSURANCE,ADL");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.LINEAR_DELIVERY))
                .isEqualTo("LINEAR_DELIVERY:CROSS,LINEAR_DELIVERY:ISOLATED,SETTLEMENT,CURSOR");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.INVERSE_DELIVERY))
                .isEqualTo("INVERSE_DELIVERY:CROSS,INVERSE_DELIVERY:ISOLATED,SETTLEMENT,CURSOR");
        assertThat(W4LifecycleQaMain.requiredRows(ProductLine.OPTION))
                .isEqualTo("OPTION:CALL:ITM,OPTION:CALL:ATM,OPTION:CALL:OTM,OPTION:PUT:ITM,OPTION:PUT:ATM,OPTION:PUT:OTM");
    }

    @Test
    void realModesFailClosedUntilProviderFaultAndReconciliationBoundariesExist() {
        assertThatThrownBy(() -> W4LifecycleQaMain.requireProviderCapabilities("execute"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("W4_REAL_CAPABILITY_PENDING")
                .hasMessageContaining("provider-to-core-lifecycle")
                .hasMessageContaining("cursor-repeat-gap")
                .hasMessageContaining("pg-selected")
                .hasMessageContaining("maker-user-treasury-reconciliation");
    }

    @Test
    void realDriverFailsBeforeConnectingOrWritingAManifest() throws Exception {
        Path manifest = Files.createTempFile("w4-real-pending-", ".env");
        Files.delete(manifest);
        String previousProductLine = System.getProperty("surprising.aeron.product-line");
        String previousManifest = System.getProperty("surprising.aeron.w4-manifest");
        String previousMode = System.getProperty("surprising.aeron.w4-mode");
        try {
            System.setProperty("surprising.aeron.product-line", "SPOT");
            System.setProperty("surprising.aeron.w4-manifest", manifest.toString());
            System.setProperty("surprising.aeron.w4-mode", "execute");

            assertThatThrownBy(() -> W4LifecycleQaMain.main(new String[0]))
                    .isInstanceOf(IllegalStateException.class)
                    .hasMessageContaining("W4_REAL_CAPABILITY_PENDING");
            assertThat(Files.exists(manifest)).isFalse();
        } finally {
            restoreProperty("surprising.aeron.product-line", previousProductLine);
            restoreProperty("surprising.aeron.w4-manifest", previousManifest);
            restoreProperty("surprising.aeron.w4-mode", previousMode);
        }
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
