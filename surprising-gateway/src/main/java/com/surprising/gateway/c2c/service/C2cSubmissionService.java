package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.C2cAccess;
import com.surprising.gateway.c2c.C2cAds;
import com.surprising.gateway.c2c.C2cOrders;
import com.surprising.gateway.c2c.C2cProblem;
import com.surprising.gateway.c2c.C2cSubmissions;
import com.surprising.gateway.c2c.model.C2cAdPublish;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原请求先持久接收，再进入实际业务事务；调用方事务须暂停，避免接收/拒绝随业务失败一起回滚。 */
@Service
@Transactional(propagation=Propagation.NOT_SUPPORTED)
public class C2cSubmissionService {
    private final C2cSubmissions submissions;
    private final C2cAds ads;
    private final C2cOrders orders;
    private final C2cAccess access;
    private final C2cQueryService queries;

    public C2cSubmissionService(C2cSubmissions submissions,C2cAds ads,C2cOrders orders,C2cAccess access,C2cQueryService queries) {
        this.submissions=submissions;this.ads=ads;this.orders=orders;this.access=access;this.queries=queries;
    }
    public Map<String,Object> publish(JwtPrincipal user,String authorization,String key,String hash,C2cAdPublish payload,String email,String totp) {
        var receipt=submissions.admit(user.userId(),key,"AD_PUBLISH",hash);
        if(receipt.status().equals("PROCESSING")) {
            try {
                access.requireNewTrading();ads.publish(user,authorization,key,hash,payload,email,totp);
            } catch(IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
                reject(user.userId(),key,"AD_PUBLISH",hash,failure);throw failure;
            }
        }
        return queries.submission(user.userId(),key,"AD_PUBLISH",hash);
    }
    public Map<String,Object> create(JwtPrincipal user,String authorization,String key,UUID quote,String hash,String email,String totp) {
        var receipt=submissions.admit(user.userId(),key,"ORDER_CREATE",hash);
        if(receipt.status().equals("PROCESSING")) {
            try {
                access.requireNewTrading();orders.create(user,authorization,key,quote,email,totp);
            } catch(IllegalArgumentException | IllegalStateException | ArithmeticException failure) {
                reject(user.userId(),key,"ORDER_CREATE",hash,failure);throw failure;
            }
        }
        return queries.submission(user.userId(),key,"ORDER_CREATE",hash);
    }
    /** 格式错误仍按原接收协议留拒绝回执；已经停止/成功的原请求只返回原结果。 */
    public Map<String,Object> rejectInput(long user,String key,String kind,String hash,RuntimeException failure) {
        var receipt=submissions.admit(user,key,kind,hash);
        if(!receipt.status().equals("PROCESSING")) return queries.submission(user,key,kind,hash);
        reject(user,key,kind,hash,failure);throw failure;
    }
    public Map<String,Object> stop(long user,String key,String kind,String hash) {
        submissions.stop(user,key,kind,hash);return queries.submission(user,key,kind,hash);
    }
    private void reject(long user,String key,String kind,String hash,RuntimeException failure) {
        if(failure instanceof C2cProblem problem && problem.retryable()) return;
        submissions.reject(user,key,kind,hash,"VALIDATION_REJECTED",
                failure instanceof ArithmeticException?"金额或数量超出精度、范围限制":failure.getMessage());
    }
}
