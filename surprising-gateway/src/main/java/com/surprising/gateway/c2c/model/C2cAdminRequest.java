package com.surprising.gateway.c2c.model;

import com.surprising.gateway.provider.auth.model.AdminApprovalReference;

/** Controller 绑定的审批引用及请求事实；正文单独保持原始字节。 */
public record C2cAdminRequest(AdminApprovalReference approval, String method, String path, String query, String userAgent, String ip) {
}
