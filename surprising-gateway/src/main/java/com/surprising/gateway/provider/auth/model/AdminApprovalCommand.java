package com.surprising.gateway.provider.auth.model;

/** Controller 已绑定的创建审批命令，不包含身份、有效期或持久化状态。 */
public record AdminApprovalCommand(String service, String httpMethod, String requestPath,
                                   String queryString, String requestBodySha256, String reason) {
}
