package com.surprising.gateway.provider.exception;

import com.surprising.product.api.ProductLine;

/** 原生产品就绪失败；禁止开始该产品的账户或交易操作，由协议入口决定响应。 */
public class ProductUnavailableException extends RuntimeException {
    private final ProductLine product;
    public ProductUnavailableException(ProductLine product) {
        super("product is not enabled: " + product);
        this.product = product;
    }
    public ProductLine product() { return product; }
}
