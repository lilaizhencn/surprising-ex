package com.surprising.product.api;

public final class ProductLineConfiguration {

    private ProductLineConfiguration() {
    }

    /**
     * 校验一个服务的产品线配置。
     *
     * @param productLine 产品线
     * @param component 组件名称，用于错误信息
     */
    public static ProductLine require(ProductLine productLine, String component) {
        if (productLine == null) {
            throw new IllegalStateException(component + " 必须显式配置 product-line，禁止使用默认产品线");
        }
        return productLine;
    }

    /**
     * 校验请求的产品线必须属于当前服务实例。
     */
    public static void requireSame(ProductLine configured,
                                   ProductLine requested,
                                   String component) {
        if (configured == null) {
            throw new IllegalStateException(component + " 未配置当前产品线");
        }
        if (requested == null) {
            throw new IllegalArgumentException(component + " 请求必须携带 productLine");
        }
        if (configured != requested) {
            throw new IllegalArgumentException(component + " 不允许跨产品线访问：当前="
                    + configured + "，请求=" + requested);
        }
    }
}
