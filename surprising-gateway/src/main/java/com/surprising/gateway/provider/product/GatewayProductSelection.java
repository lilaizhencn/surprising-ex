package com.surprising.gateway.provider.product;

import com.surprising.product.api.ProductLine;
import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** 入口只解析一次产品归属；任何来源冲突都拒绝，不能用头覆盖正文或批量请求中的其他产品。 */
@Component
public final class GatewayProductSelection {
    private static final List<String> SELECTORS = List.of("productLine", "product-line", "product_line",
            "accountType", "account-type", "account_type", "contractType", "contract-type", "contract_type");
    private final GatewayProductsProperties products;
    private final ObjectMapper mapper;
    public GatewayProductSelection(GatewayProductsProperties products, ObjectMapper mapper) {
        this.products = products;
        this.mapper = mapper;
    }
    public ProductLine resolve(HttpServletRequest request, byte[] body, boolean productRequired) {
        Set<ProductLine> selected = EnumSet.noneOf(ProductLine.class);
        for (String name : List.of("X-Product-Line", "X-Account-Type", "X-Contract-Type")) {
            var headers = request.getHeaders(name);
            if (headers != null) while (headers.hasMoreElements()) add(selected, headers.nextElement());
        }
        for (String name : SELECTORS) {
            String[] values = request.getParameterValues(name);
            if (values != null) for (String value : values) add(selected, value);
        }
        String path = request.getRequestURI();
        if (path.equals("/api/v3") || path.startsWith("/api/v3/")) selected.add(ProductLine.SPOT);
        if (path.startsWith("/fapi/")) selected.add(ProductLine.LINEAR_PERPETUAL);
        if (path.startsWith("/dapi/")) selected.add(ProductLine.INVERSE_PERPETUAL);
        if (path.startsWith("/eapi/")) selected.add(ProductLine.OPTION);
        if (body != null && body.length != 0) {
            try { collect(selected, mapper.readTree(body)); }
            catch (tools.jackson.core.JacksonException ex) { throw badRequest("invalid JSON body"); }
        }
        if (selected.size() > 1) throw badRequest("conflicting product selectors");
        ProductLine product = selected.stream().findFirst().orElse(null);
        if (product == null && productRequired) {
            if (products.getEnabled().size() != 1) throw badRequest("productLine is required for a multi-product Gateway");
            product = products.getEnabled().getFirst();
        }
        if (product != null) products.requireEnabled(product);
        return product;
    }
    private void collect(Set<ProductLine> selected, JsonNode node) {
        if (node == null) return;
        if (node.isObject()) for (String name : SELECTORS) {
            JsonNode value = node.get(name);
            if (value != null && !value.isNull()) {
                if (!value.isString()) throw badRequest("product selector must be a string");
                add(selected, value.asText());
            }
        }
        if (node.isObject() || node.isArray()) for (JsonNode child : node) collect(selected, child);
    }
    private void add(Set<ProductLine> selected, String value) {
        if (value == null || value.isBlank()) return;
        if ("FUNDING".equalsIgnoreCase(value)) { selected.add(ProductLine.SPOT); return; }
        selected.add(ProductLine.fromExternalCode(value).orElseThrow(() -> badRequest("unsupported product line: " + value)));
    }
    private ResponseStatusException badRequest(String reason) { return new ResponseStatusException(HttpStatus.BAD_REQUEST, reason); }
}
