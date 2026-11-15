package com.surprising.insurance.provider.config;

import lombok.Getter;
import lombok.Setter;

import com.surprising.product.api.ProductLine;

@Getter
public class InsuranceProperties {

    private ProductLine productLine = ProductLine.LINEAR_PERPETUAL;
    @Setter
    private volatile Coverage coverage = new Coverage();

    public void setProductLine(ProductLine productLine) {
        this.productLine = productLine == null ? ProductLine.LINEAR_PERPETUAL : productLine;
    }

    public String getAccountType() {
        return productLine.accountTypeCode();
    }

    @Getter
    @Setter
    public static class Coverage {
        private boolean enabled = true;
        private long scanDelayMs = 1000L;
        private int batchSize = 100;

    }
}
