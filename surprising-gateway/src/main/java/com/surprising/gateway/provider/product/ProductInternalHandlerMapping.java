package com.surprising.gateway.provider.product;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.handler.AbstractHandlerMapping;

/** 保留独立做市、价格和生命周期服务的内部 HTTP 契约，处理器来自选中产品线的业务容器。 */
@Component
public final class ProductInternalHandlerMapping extends AbstractHandlerMapping {
    private final GatewayProductServices products;
    private final GatewayProductSelection selection;
    public ProductInternalHandlerMapping(GatewayProductServices products, GatewayProductSelection selection) {
        this.products = products;
        this.selection = selection;
        setOrder(-1);
    }
    static boolean isProductEndpoint(String path) {
        return path.startsWith("/api/v1/accounts/") || path.startsWith("/api/v1/trading/")
                || path.equals("/api/v1/trading/orders") || path.startsWith("/internal/v1/accounts/")
                || path.startsWith("/internal/v1/trading/");
    }
    @Override protected Object getHandlerInternal(HttpServletRequest request) throws Exception {
        if (!isProductEndpoint(request.getRequestURI())) return null;
        var product = selection.resolve(request, (byte[]) request.getAttribute(ProductRequestBodyFilter.BODY), true);
        return products.internalMapping(product).getHandler(request);
    }
}
