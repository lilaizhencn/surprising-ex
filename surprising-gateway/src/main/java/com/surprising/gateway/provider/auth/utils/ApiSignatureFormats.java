package com.surprising.gateway.provider.auth.utils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.ArrayList;
import java.util.Locale;

/** API签名原文字节编码，不读取请求或验证业务权限。 */
public final class ApiSignatureFormats {
    private ApiSignatureFormats() {}
    public static String canonicalQuery(String queryString) {
        if (queryString == null || queryString.isBlank()) {
            return "";
        }
        return java.util.Arrays.stream(queryString.split("&"))
                .filter(item -> {
                    int separator = item.indexOf('=');
                    String key = separator < 0 ? item : item.substring(0, separator);
                    return !"signature".equals(key);
                })
                .reduce((left, right) -> left + "&" + right)
                .orElse("");
    }

    public static String canonicalRequest(String method, String path, java.util.Map<String, String[]> parameterMap) {
        return method.toUpperCase(Locale.ROOT) + "\n" + path + "\n" + canonicalParameters(parameterMap);
    }

    public static String canonicalParameters(java.util.Map<String, String[]> parameterMap) {
        List<String> parameters = new ArrayList<>();
        parameterMap.entrySet().stream()
                .filter(entry -> !"signature".equals(entry.getKey()))
                .sorted(java.util.Map.Entry.comparingByKey())
                .forEach(entry -> {
                    String[] values = entry.getValue();
                    if (values == null || values.length == 0) {
                        parameters.add(encodeParameter(entry.getKey(), ""));
                    } else {
                        for (String value : values) {
                            parameters.add(encodeParameter(entry.getKey(), value == null ? "" : value));
                        }
                    }
                });
        return String.join("&", parameters);
    }

    private static String encodeParameter(String key, String value) {
        return java.net.URLEncoder.encode(key, StandardCharsets.UTF_8)
                + "=" + java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

}
