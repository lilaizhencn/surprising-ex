package com.surprising.product.api;

/**
 * 产品线运行配置校验工具。
 *
 * <p>交易服务必须显式开启产品线 Topic，并且不能依赖默认永续配置启动，
 * 否则不同产品线可能误消费同一组旧 Topic。</p>
 */
public final class ProductLineConfiguration {

    private ProductLineConfiguration() {
    }

    /**
     * 校验一个服务的产品线配置。
     *
     * @param productLine 产品线
     * @param productTopicsEnabled 是否启用产品线隔离 Topic
     * @param component 组件名称，用于错误信息
     */
    public static ProductLine require(ProductLine productLine,
                                      boolean productTopicsEnabled,
                                      String component) {
        if (productLine == null) {
            throw new IllegalStateException(component + " 必须显式配置 product-line，禁止使用默认产品线");
        }
        if (!productTopicsEnabled) {
            throw new IllegalStateException(component + " 必须显式启用 product-topics-enabled=true");
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
