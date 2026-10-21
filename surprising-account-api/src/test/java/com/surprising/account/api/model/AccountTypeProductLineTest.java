package com.surprising.account.api.model;

import com.surprising.product.api.ProductLine;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTypeProductLineTest {

    @Test
    void mapsTradingAccountTypesToProductLines() {
        assertThat(AccountType.SPOT.productLine()).contains(ProductLine.SPOT);
        assertThat(AccountType.USDT_PERPETUAL.productLine()).contains(ProductLine.LINEAR_PERPETUAL);
        assertThat(AccountType.COIN_PERPETUAL.productLine()).contains(ProductLine.INVERSE_PERPETUAL);
        assertThat(AccountType.USDT_DELIVERY.productLine()).contains(ProductLine.LINEAR_DELIVERY);
        assertThat(AccountType.COIN_DELIVERY.productLine()).contains(ProductLine.INVERSE_DELIVERY);
        assertThat(AccountType.OPTION.productLine()).contains(ProductLine.OPTION);
    }

    @Test
    void fundingAccountIsNotATradingProductLine() {
        assertThat(AccountType.FUNDING.productLine()).isEmpty();
    }
}
