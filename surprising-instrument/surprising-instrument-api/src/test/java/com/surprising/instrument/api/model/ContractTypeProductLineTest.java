package com.surprising.instrument.api.model;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ContractTypeProductLineTest {

    @Test
    void mapsContractTypesToProductLines() {
        assertThat(ContractType.SPOT.productLine()).isEqualTo(ProductLine.SPOT);
        assertThat(ContractType.LINEAR_PERPETUAL.productLine()).isEqualTo(ProductLine.LINEAR_PERPETUAL);
        assertThat(ContractType.INVERSE_PERPETUAL.productLine()).isEqualTo(ProductLine.INVERSE_PERPETUAL);
        assertThat(ContractType.LINEAR_DELIVERY.productLine()).isEqualTo(ProductLine.LINEAR_DELIVERY);
        assertThat(ContractType.INVERSE_DELIVERY.productLine()).isEqualTo(ProductLine.INVERSE_DELIVERY);
        assertThat(ContractType.VANILLA_OPTION.productLine()).isEqualTo(ProductLine.OPTION);
    }

    @Test
    void classifiesContractFamilies() {
        assertThat(ContractType.LINEAR_PERPETUAL.isPerpetual()).isTrue();
        assertThat(ContractType.LINEAR_DELIVERY.isDelivery()).isTrue();
        assertThat(ContractType.VANILLA_OPTION.isOption()).isTrue();
        assertThat(ContractType.LINEAR_DELIVERY.isLinear()).isTrue();
        assertThat(ContractType.INVERSE_DELIVERY.isInverse()).isTrue();
        assertThat(ContractType.INVERSE_DELIVERY.usesPriceQuantityNotional()).isFalse();
    }
}
