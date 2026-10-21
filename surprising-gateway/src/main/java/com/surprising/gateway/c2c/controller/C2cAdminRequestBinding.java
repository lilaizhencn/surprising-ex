package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.provider.auth.controller.AdminApprovalRequestBinding;
import com.surprising.gateway.c2c.model.C2cAdminRequest;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.stereotype.Component;

/** 三个后台HTTP入口共享同一审批头配置和原始请求绑定。 */
@Component
public final class C2cAdminRequestBinding {
    private final AdminApprovalService approvals;
    public C2cAdminRequestBinding(AdminApprovalService approvals) { this.approvals = approvals; }
    public C2cAdminRequest bind(HttpServletRequest request) {
        return new C2cAdminRequest(AdminApprovalRequestBinding.reference(request.getHeader(approvals.approvalHeaderName())), request.getMethod(), request.getRequestURI(),
                request.getQueryString(), request.getHeader("User-Agent"), request.getRemoteAddr());
    }
}
