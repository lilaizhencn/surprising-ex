package com.surprising.product.api;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ProductLineTest {

    @Test
    void mapsAccountTypesToProductLines() {
        assertThat(ProductLine.requireAccountTypeCode("SPOT")).isEqualTo(ProductLine.SPOT);
        assertThat(ProductLine.requireAccountTypeCode("usdt_perpetual")).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(ProductLine.requireAccountTypeCode("COIN_PERPETUAL")).isEqualTo(ProductLine.INVERSE_PERPETUAL);
        assertThat(ProductLine.requireAccountTypeCode("USDT_DELIVERY")).isEqualTo(ProductLine.LINEAR_DELIVERY);
        assertThat(ProductLine.requireAccountTypeCode("COIN_DELIVERY")).isEqualTo(ProductLine.INVERSE_DELIVERY);
        assertThat(ProductLine.requireAccountTypeCode("OPTION")).isEqualTo(ProductLine.OPTION);
    }

    @Test
    void exposesProductCapabilities() {
        assertThat(ProductLine.SPOT.isDerivative()).isFalse();
        assertThat(ProductLine.LINEAR_PERPETUAL.isFundingProduct()).isTrue();
        assertThat(ProductLine.LINEAR_DELIVERY.isFundingProduct()).isFalse();
        assertThat(ProductLine.LINEAR_DELIVERY.isDeliveryProduct()).isTrue();
        assertThat(ProductLine.OPTION.isOptionProduct()).isTrue();
    }

    @Test
    void rejectsUnknownAccountType() {
        assertThatThrownBy(() -> ProductLine.requireAccountTypeCode("MARGIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported product account type");
    }
}
