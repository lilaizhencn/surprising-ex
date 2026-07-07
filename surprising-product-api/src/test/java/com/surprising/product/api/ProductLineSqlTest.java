package com.surprising.product.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProductLineSqlTest {

    @Test
    void mapsContractTypeExpressionToProductLineCase() {
        String sql = ProductLineSql.contractTypeProductLineCase("i.contract_type");

        assertThat(sql)
                .startsWith("CASE i.contract_type")
                .contains("WHEN 'SPOT' THEN 'SPOT'")
                .contains("WHEN 'LINEAR_DELIVERY' THEN 'LINEAR_DELIVERY'")
                .contains("WHEN 'VANILLA_OPTION' THEN 'OPTION'")
                .contains("ELSE 'LINEAR_PERPETUAL'")
                .endsWith("END");
    }

    @Test
    void defaultsBlankExpressionToContractTypeColumn() {
        assertThat(ProductLineSql.contractTypeProductLineCase(" "))
                .startsWith("CASE contract_type");
    }

    @Test
    void mapsContractTypeExpressionToAccountTypeCase() {
        String sql = ProductLineSql.contractTypeAccountTypeCase("i.contract_type");

        assertThat(sql)
                .startsWith("CASE i.contract_type")
                .contains("WHEN 'SPOT' THEN 'SPOT'")
                .contains("WHEN 'INVERSE_PERPETUAL' THEN 'COIN_PERPETUAL'")
                .contains("WHEN 'LINEAR_DELIVERY' THEN 'USDT_DELIVERY'")
                .contains("WHEN 'INVERSE_DELIVERY' THEN 'COIN_DELIVERY'")
                .contains("WHEN 'VANILLA_OPTION' THEN 'OPTION'")
                .contains("ELSE 'USDT_PERPETUAL'")
                .endsWith("END");
    }

    @Test
    void defaultsBlankAccountTypeExpressionToContractTypeColumn() {
        assertThat(ProductLineSql.contractTypeAccountTypeCase(" "))
                .startsWith("CASE contract_type");
    }
}
