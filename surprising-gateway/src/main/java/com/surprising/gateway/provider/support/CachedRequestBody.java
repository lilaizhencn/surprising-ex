package com.surprising.gateway.provider.support;

import jakarta.servlet.http.HttpServletRequest;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Shared request attribute keys and lazy JSON tree cache populated by ProductRequestBodyFilter. */
public final class CachedRequestBody {

    public static final String BODY = "com.surprising.gateway.provider.interceptor.ProductRequestBodyFilter.body";
    private static final String JSON_TREE = "com.surprising.gateway.provider.interceptor.ProductRequestBodyFilter.jsonTree";

    private record CachedJsonTree(byte[] body, JsonNode tree) {}

    private CachedRequestBody() {}

    public static JsonNode requestTree(HttpServletRequest request, byte[] body, ObjectMapper mapper) {
        if (body == null || body.length == 0) return null;
        if (request != null && request.getAttribute(JSON_TREE) instanceof CachedJsonTree cached && cached.body() == body) {
            return cached.tree();
        }
        JsonNode tree = mapper.readTree(body);
        if (request != null) {
            request.setAttribute(JSON_TREE, new CachedJsonTree(body, tree));
        }
        return tree;
    }
}
