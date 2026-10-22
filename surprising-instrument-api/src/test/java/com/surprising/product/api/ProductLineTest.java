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
    void mapsContractTypesToProductLines() {
        assertThat(ProductLine.requireContractTypeCode("SPOT")).isEqualTo(ProductLine.SPOT);
        assertThat(ProductLine.requireContractTypeCode("linear_perpetual")).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(ProductLine.requireContractTypeCode("INVERSE_PERPETUAL")).isEqualTo(ProductLine.INVERSE_PERPETUAL);
        assertThat(ProductLine.requireContractTypeCode("LINEAR_DELIVERY")).isEqualTo(ProductLine.LINEAR_DELIVERY);
        assertThat(ProductLine.requireContractTypeCode("INVERSE_DELIVERY")).isEqualTo(ProductLine.INVERSE_DELIVERY);
        assertThat(ProductLine.requireContractTypeCode("VANILLA_OPTION")).isEqualTo(ProductLine.OPTION);
        assertThat(ProductLine.OPTION.contractTypeCode()).isEqualTo("VANILLA_OPTION");
    }

    @Test
    void mapsExternalProductCodesToProductLines() {
        assertThat(ProductLine.requireExternalCode("linear-perp")).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(ProductLine.requireExternalCode("LINEAR_PERPETUAL")).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(ProductLine.requireExternalCode("COIN_PERPETUAL")).isEqualTo(ProductLine.INVERSE_PERPETUAL);
        assertThat(ProductLine.requireExternalCode("VANILLA_OPTION")).isEqualTo(ProductLine.OPTION);
        assertThat(ProductLine.fromExternalCode("  option  ")).contains(ProductLine.OPTION);
    }

    @Test
    void exposesProductCapabilities() {
        assertThat(ProductLine.SPOT.isDerivative()).isFalse();
        assertThat(ProductLine.LINEAR_PERPETUAL.isFundingProduct()).isTrue();
        assertThat(ProductLine.LINEAR_DELIVERY.isFundingProduct()).isFalse();
        assertThat(ProductLine.LINEAR_DELIVERY.isDeliveryProduct()).isTrue();
        assertThat(ProductLine.OPTION.isOptionProduct()).isTrue();
        assertThat(ProductLine.SPOT.supportsUserPositionMarginFlow()).isFalse();
        assertThat(ProductLine.LINEAR_PERPETUAL.supportsUserPositionMarginFlow()).isTrue();
        assertThat(ProductLine.INVERSE_PERPETUAL.supportsUserPositionMarginFlow()).isTrue();
        assertThat(ProductLine.LINEAR_DELIVERY.supportsUserPositionMarginFlow()).isTrue();
        assertThat(ProductLine.INVERSE_DELIVERY.supportsUserPositionMarginFlow()).isTrue();
        assertThat(ProductLine.OPTION.supportsUserPositionMarginFlow()).isTrue();
    }

    @Test
    void rejectsUnknownAccountType() {
        assertThatThrownBy(() -> ProductLine.requireAccountTypeCode("MARGIN"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported product account type");
    }

    @Test
    void rejectsUnknownContractType() {
        assertThatThrownBy(() -> ProductLine.requireContractTypeCode("QUANTO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported product contract type");
    }

    @Test
    void rejectsUnknownExternalProductCode() {
        assertThatThrownBy(() -> ProductLine.requireExternalCode("QUANTO"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unsupported product filter");
    }

    @Test
    void requiresExplicitProductLineConfiguration() {
        assertThat(ProductLineConfiguration.require(ProductLine.SPOT, "account"))
                .isEqualTo(ProductLine.SPOT);
        assertThatThrownBy(() -> ProductLineConfiguration.require(null, "account"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("必须显式配置 product-line");
    }

    @Test
    void rejectsCrossProductLineAccess() {
        ProductLineConfiguration.requireSame(ProductLine.OPTION, ProductLine.OPTION, "account");
        assertThatThrownBy(() -> ProductLineConfiguration.requireSame(
                ProductLine.OPTION, ProductLine.SPOT, "account.position-mode"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("不允许跨产品线访问");
    }
}
