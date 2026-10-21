package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;

import com.surprising.gateway.c2c.repository.C2cAdsRepository;

import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.model.C2cPaymentDetails;
import com.surprising.gateway.c2c.model.C2cPaymentEnvelope;
import com.surprising.gateway.c2c.repository.C2cAttachmentRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentMethodRepository;
import com.surprising.gateway.provider.auth.model.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.service.ComplianceService;
import com.surprising.gateway.provider.auth.service.TotpService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 收款账号仅本人/订单双方/有权限客服可见；变更需重审，在途订单仍使用原快照。 */
@Service
public class C2cPaymentAccounts {
    public record Details(String holderName, String accountNumber, String bankName, UUID qrAttachmentId) { }
    private final C2cPaymentAccountRepository accounts;
    private final C2cPaymentMethodRepository methods;
    private final C2cAdsRepository ads;
    private final C2cAttachmentRepository attachments;
    private final TotpService secrets;
    private final ObjectMapper json;
    private final ComplianceService compliance;
    private final C2cAccess access;
    public C2cPaymentAccounts(C2cPaymentAccountRepository accounts, C2cPaymentMethodRepository methods,
                              C2cAdsRepository ads, C2cAttachmentRepository attachments,
                              TotpService secrets, ObjectMapper json, ComplianceService compliance,C2cAccess access) {
        this.access=access;
        this.accounts=accounts; this.methods=methods; this.ads=ads; this.attachments=attachments;
        this.secrets=secrets; this.json=json; this.compliance=compliance;
    }

    public List<C2cPaymentAccount> list(long userId) {
        return accounts.byUser(userId);
    }

    public C2cPaymentAccount get(long userId, UUID id, boolean requireApproved) {
        var account=accounts.find(userId,id).orElseThrow(()->new IllegalArgumentException("收款账号不存在"));
        if (requireApproved) {
            if (!"APPROVED".equals(account.status())) throw C2cProblem.conflict("收款账号需先通过实名审核");
            if (methods.available(account.method(),account.fiat()).isEmpty()) throw C2cProblem.conflict("收款方式当前不可用");
        }
        return account;
    }

    @Transactional
    public C2cPaymentAccount saveVerified(JwtPrincipal user,UUID id,long revision,String fiat,String method,Details details,String email,String totp) {
        access.verifySettings(user,email,totp);return save(user.userId(),id,revision,fiat,method,details);
    }
    @Transactional
    public void disableVerified(JwtPrincipal user,UUID id,long revision,String email,String totp) {
        access.verifySettings(user,email,totp);disable(user.userId(),id,revision);
    }

    @Transactional
    public C2cPaymentAccount save(long userId, UUID id, long revision, String fiat, String method, Details details) {
        validateDetails(userId,fiat,method,details);
        var envelope = new C2cPaymentEnvelope("C2C_PAYMENT",Long.toString(userId),fiat,method,
                Normalizer.normalize(details.holderName().trim(),Normalizer.Form.NFKC),details.accountNumber().trim(),
                details.bankName()==null?"":details.bankName().trim(),details.qrAttachmentId());
        String encrypted=secrets.encryptSecret(json.writeValueAsString(envelope));
        String number=details.accountNumber().trim();
        String masked="****"+number.substring(Math.max(0,number.length()-4));
        if (id==null) {
            if (revision!=0) throw new IllegalArgumentException("新账号版本无效");
            id=UUID.randomUUID();
            accounts.insert(id,userId,fiat,method,encrypted,masked);
        } else {
            pauseAds(userId,id);
            int changed=accounts.replaceDetails(id,userId,revision,fiat,method,encrypted,masked);
            if(changed!=1) throw C2cProblem.conflict("收款账号已变更，请刷新");
        }
        return get(userId,id,false);
    }

    @Transactional
    public void disable(long userId,UUID id,long revision) {
        pauseAds(userId,id);
        int changed=accounts.disable(id,userId,revision);
        if(changed!=1) throw C2cProblem.conflict("收款账号已变更，请刷新");
    }

    @Transactional
    public C2cPaymentAccount review(UUID id,long revision,boolean approved,String reason,JwtPrincipal administrator) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("请填写实名核验结论");
        var value=accounts.lock(id).orElseThrow(()->new IllegalArgumentException("收款账号不存在"));
        if(value.userId()==administrator.userId()) throw C2cProblem.conflict("不能审核本人的收款账号");
        if(!"PENDING".equals(value.status()) || value.revision()!=revision) throw C2cProblem.conflict("收款审核状态已变更");
        if(approved) {
            var kyc=compliance.kyc(value.userId());
            if(kyc==null || !"VERIFIED".equals(kyc.status()) || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now())))
                throw C2cProblem.conflict("账号本人尚无有效身份认证");
            var verified=details(value);
            validateDetails(value.userId(),value.fiat(),value.method(),new Details(verified.holderName(),
                    verified.accountNumber(),verified.bankName(),verified.qrAttachmentId()));
        }
        accounts.review(id,approved?"APPROVED":"REJECTED",reason.trim(),administrator.userId());
        return get(value.userId(),id,false);
    }

    public C2cPaymentDetails details(C2cPaymentAccount account) {
        var result = details(account.userId(), account.ciphertext());
        if (!account.fiat().equals(result.fiat()) || !account.method().equals(result.methodCode()))
            throw new IllegalStateException("收款方式与资料不匹配");
        return result;
    }

    public C2cPaymentDetails details(long ownerUserId, String ciphertext) {
        var envelope=json.readValue(secrets.decryptSecret(ciphertext),C2cPaymentEnvelope.class);
        if(!"C2C_PAYMENT".equals(envelope.purpose()) || !Long.toString(ownerUserId).equals(envelope.userId()))
            throw new IllegalStateException("收款资料归属不匹配");
        return new C2cPaymentDetails(envelope.fiat(),envelope.methodCode(),envelope.holderName(),envelope.accountNumber(),
                envelope.bankName(),envelope.qrAttachmentId());
    }

    private void validateDetails(long userId,String fiat,String method,Details details) {
        var methodPolicy=methods.available(method,fiat).orElseThrow(()->new IllegalArgumentException("该币种收款方式尚未开放"));
        if(methodPolicy.requiresBankName() && (details.bankName()==null || details.bankName().isBlank()
                || !details.accountNumber().trim().matches("[0-9]{8,40}"))) throw new IllegalArgumentException("请填写正确的银行名称和卡号");
        if(details.qrAttachmentId()!=null && (!methodPolicy.supportsQr() || !attachments.readyPaymentQr(details.qrAttachmentId(),userId)))
            throw new IllegalArgumentException("收款码归属不匹配");
    }

    private void pauseAds(long userId,UUID accountId) {
        // 与下单相同：先锁广告，再变更收款账号，阻止旧报价使用新账号。
        ads.pauseUsingPaymentAccount(userId,accountId);
    }

}
