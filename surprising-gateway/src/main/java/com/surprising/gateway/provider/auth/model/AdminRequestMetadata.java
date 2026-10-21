package com.surprising.gateway.provider.auth.model;

/** Controller 绑定的审批引用与完整请求身份，正文单独按原始字节匹配。 */
public record AdminRequestMetadata(AdminApprovalReference approval, String method, String requestUri, String queryString) {
}
