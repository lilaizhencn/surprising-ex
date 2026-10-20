package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.*;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.config.GatewayTraceFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

/** 后台权限、精确请求审批和审计的边界；不接受业务自行传入的管理员身份。 */
@Service
public class C2cAdminAccess {
    record Request(String approvalId,String method,String path,String query,String trace,String userAgent,String ip) {
        AdminApprovalService.AdminRequestMetadata approvalMetadata() {
            return new AdminApprovalService.AdminRequestMetadata(approvalId,method,path,query,trace);
        }
    }
    record Approved(JwtPrincipal administrator,AdminApprovalResponse approval) { }
    private final AuthService auth;
    private final AdminApprovalService approvals;
    private final AdminApprovalRepository approvalRows;
    private final AdminAuditRepository audit;
    private final GatewayUserRepository users;

    public C2cAdminAccess(AuthService auth,AdminApprovalService approvals,AdminApprovalRepository approvalRows,
                          AdminAuditRepository audit,GatewayUserRepository users) {
        this.auth=auth;this.approvals=approvals;this.approvalRows=approvalRows;this.audit=audit;this.users=users;
    }

    Request request(HttpServletRequest request) {
        Object trace=request.getAttribute(GatewayTraceFilter.TRACE_ID_ATTRIBUTE);
        return new Request(request.getHeader(approvals.approvalHeaderName()),request.getMethod(),request.getRequestURI(),
                request.getQueryString(),trace==null?null:trace.toString(),request.getHeader("User-Agent"),request.getRemoteAddr());
    }
    JwtPrincipal read(String authorization,boolean sensitive) {
        try {return auth.requireAdminPermission(authorization,sensitive?"admin.c2c.sensitive.read":"admin.c2c.read");}
        catch(IllegalArgumentException failure) {throw new C2cProblem(HttpStatus.UNAUTHORIZED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有此 C2C 权限");}
    }
    JwtPrincipal write(String authorization,Request request,byte[] body) {
        try {return approvals.requireWrite(authorization,"admin.c2c.write","c2c",request.approvalMetadata(),body);}
        catch(AdminApprovalService.AdminApprovalRequiredException failure) {throw approvalRequired();}
        catch(IllegalArgumentException failure) {throw new C2cProblem(HttpStatus.UNAUTHORIZED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有 C2C 管理权限");}
    }
    Approved resolve(String authorization,Request request,byte[] body) {
        final JwtPrincipal administrator;
        try {administrator=auth.requireAdminPermission(authorization,"admin.c2c.resolve");}
        catch(IllegalArgumentException failure) {throw new C2cProblem(HttpStatus.UNAUTHORIZED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有申诉裁决权限");}
        // 资金裁决始终双人审批；不受普通后台高风险开关关闭的影响。
        String value=request.approvalId();
        if(value==null || !value.matches("[1-9][0-9]{0,18}")) throw approvalRequired();
        try {
            long id=Long.parseLong(value);
            var approved=approvalRows.consumeApproved(id,administrator.userId(),"c2c",request.method(),request.path(),
                    request.query(),hash(body),request.trace(),Instant.now());
            if(approved.approverUserId()==null || approved.approverUserId()==administrator.userId()) throw approvalRequired();
            var reviewer=users.find(approved.approverUserId());
            if(reviewer.isEmpty() || "FROZEN".equals(reviewer.get().status())) throw approvalRequired();
            auth.requireAdminPermission(approved.approverUserId(),List.of(),"admin.c2c.resolve");
            return new Approved(administrator,approved);
        } catch(IllegalArgumentException | IllegalStateException failure) {throw approvalRequired();}
    }
    void audit(JwtPrincipal administrator,Request request,byte[] body) {
        audit.recordRequired(record(administrator,request,body,200,true,null));
    }
    void failure(String authorization,Request request,byte[] body,RuntimeException failure) {
        JwtPrincipal administrator=null;
        try {administrator=auth.authenticateAdminBearer(authorization);} catch(IllegalArgumentException | IllegalStateException ignored) { }
        int status=failure instanceof C2cProblem problem?problem.status.value():failure instanceof IllegalArgumentException?400:failure instanceof IllegalStateException?409:503;
        // 在原业务事务回滚后记录失败，只留异常类别与正文摘要，不写验证码或收款资料。
        audit.recordRequired(record(administrator,request,body,status,false,failure.getClass().getSimpleName()));
    }
    private AdminAuditRepository.AdminOperationRecord record(JwtPrincipal principal,Request request,byte[] body,int status,boolean success,String error) {
        return new AdminAuditRepository.AdminOperationRecord(principal==null?null:principal.userId(),principal==null?null:principal.username(),
                principal==null?List.of():principal.roles(),"c2c",request.method(),request.path(),request.query(),null,hash(body),status,
                null,success,error,request.trace(),request.userAgent(),request.ip(),Instant.now());
    }
    static String hash(byte[] body) {
        if(body==null || body.length==0) return null;
        try {return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(body));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private static C2cProblem approvalRequired() {
        return new C2cProblem(HttpStatus.FORBIDDEN,"C2C_APPROVAL_REQUIRED","需要另一位有权限的管理员审批当前完整请求");
    }
}
