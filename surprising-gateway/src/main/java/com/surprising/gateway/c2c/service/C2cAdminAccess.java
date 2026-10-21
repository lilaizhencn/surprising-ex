package com.surprising.gateway.c2c.service;

import com.surprising.gateway.provider.exception.AdminApprovalRequiredException;
import com.surprising.gateway.provider.auth.model.AdminRequestMetadata;
import com.surprising.gateway.provider.auth.repository.AdminAuditRepository;
import com.surprising.gateway.provider.auth.repository.GatewayUserRepository;
import com.surprising.gateway.provider.auth.service.AdminApprovalService;
import com.surprising.gateway.provider.auth.service.AuthService;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.model.C2cAdminRequest;

import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.model.AuthModels.AdminApprovalResponse;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import java.time.Instant;
import java.util.List;
import org.springframework.stereotype.Service;

/** 后台权限、精确请求审批和审计的边界；不接受业务自行传入的管理员身份。 */
@Service
public class C2cAdminAccess {
    public record Approved(JwtPrincipal administrator,AdminApprovalResponse approval) { }
    private final AuthService auth;
    private final AdminApprovalService approvals;
    private final AdminAuditRepository audit;
    private final GatewayUserRepository users;

    public C2cAdminAccess(AuthService auth,AdminApprovalService approvals,
                          AdminAuditRepository audit,GatewayUserRepository users) {
        this.auth=auth;this.approvals=approvals;this.audit=audit;this.users=users;
    }

    public JwtPrincipal read(String authorization,boolean sensitive) {
        try {return auth.requireAdminPermission(authorization,sensitive?"admin.c2c.sensitive.read":"admin.c2c.read");}
        catch(IllegalArgumentException failure) {throw new C2cProblem(C2cProblem.Reason.AUTHENTICATION_REQUIRED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有此 C2C 权限");}
    }
    public JwtPrincipal write(String authorization,C2cAdminRequest request,byte[] body) {
        try {return approvals.requireWrite(authorization,"admin.c2c.write","c2c",new AdminRequestMetadata(request.approval(), request.method(), request.path(), request.query()),body);}
        catch(AdminApprovalRequiredException failure) {throw approvalRequired();}
        catch(IllegalArgumentException failure) {throw new C2cProblem(C2cProblem.Reason.AUTHENTICATION_REQUIRED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有 C2C 管理权限");}
    }
    public Approved resolve(String authorization,C2cAdminRequest request,byte[] body) {
        final JwtPrincipal administrator;
        try {administrator=auth.requireAdminPermission(authorization,"admin.c2c.resolve");}
        catch(IllegalArgumentException failure) {throw new C2cProblem(C2cProblem.Reason.AUTHENTICATION_REQUIRED,"C2C_AUTH_REQUIRED","请使用有效后台登录");}
        catch(IllegalStateException failure) {throw C2cProblem.forbidden("当前后台账号没有申诉裁决权限");}
        // 资金裁决始终双人审批；不受普通后台高风险开关关闭的影响。
        var reference = request.approval();
        if (reference == null || !reference.canonicalPositive() || reference.value() == null) throw approvalRequired();
        try {
            long id = reference.value();
            var approved=approvals.consumeApproved(id,administrator.userId(),"c2c",request.method(),request.path(),
                    request.query(),hash(body),Instant.now());
            if(approved.approverUserId()==null || approved.approverUserId()==administrator.userId()) throw approvalRequired();
            var reviewer=users.find(approved.approverUserId());
            if(reviewer.isEmpty() || "FROZEN".equals(reviewer.get().status())) throw approvalRequired();
            auth.requireAdminPermission(approved.approverUserId(),List.of(),"admin.c2c.resolve");
            return new Approved(administrator,approved);
        } catch(IllegalArgumentException | IllegalStateException failure) {throw approvalRequired();}
    }
    public void audit(JwtPrincipal administrator,C2cAdminRequest request,byte[] body) {
        try {audit.recordRequired(record(administrator,request,body,200,true,null));}
        catch(org.springframework.dao.DataAccessException failure) {throw C2cProblem.unavailable("审计暂不可用，请稍后重试");}
    }
    public void failure(String authorization,C2cAdminRequest request,byte[] body,int status,String failureType) {
        JwtPrincipal administrator=null;
        try {administrator=auth.authenticateAdminBearer(authorization);} catch(IllegalArgumentException | IllegalStateException ignored) { }
        // 在原业务事务回滚后记录失败，只留异常类别与正文摘要，不写验证码或收款资料。
        audit.recordRequired(record(administrator,request,body,status,false,failureType));
    }
    private com.surprising.gateway.provider.auth.model.AdminOperationRecord record(JwtPrincipal principal,C2cAdminRequest request,byte[] body,int status,boolean success,String error) {
        return new com.surprising.gateway.provider.auth.model.AdminOperationRecord(principal==null?null:principal.userId(),principal==null?null:principal.username(),
                principal==null?List.of():principal.roles(),"c2c",request.method(),request.path(),request.query(),null,hash(body),status,
                null,success,error,request.userAgent(),request.ip(),Instant.now());
    }
    public static String hash(byte[] body) {
        if(body==null || body.length==0) return null;
        return C2cDigests.sha256(body);
    }
    private static C2cProblem approvalRequired() {
        return new C2cProblem(C2cProblem.Reason.ACCESS_DENIED,"C2C_APPROVAL_REQUIRED","需要另一位有权限的管理员审批当前完整请求");
    }
}
