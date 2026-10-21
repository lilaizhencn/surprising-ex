package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cPaymentAccount;
import com.surprising.gateway.c2c.repository.C2cAttachmentRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentAccountRepository;
import com.surprising.gateway.c2c.repository.C2cPaymentMethodRepository;
import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.TotpService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 收款账号仅本人/订单双方/有权限客服可见；变更需重审，在途订单仍使用原快照。 */
@Service
public class C2cPaymentAccounts {
    record Details(String holderName, String accountNumber, String bankName, UUID qrAttachmentId) { }
    private final C2cPaymentAccountRepository accounts;
    private final C2cPaymentMethodRepository methods;
    private final C2cAdsRepository ads;
    private final C2cAttachmentRepository attachments;
    private final TotpService secrets;
    private final ObjectMapper json;
    private final ComplianceService compliance;
    public C2cPaymentAccounts(C2cPaymentAccountRepository accounts, C2cPaymentMethodRepository methods,
                              C2cAdsRepository ads, C2cAttachmentRepository attachments,
                              TotpService secrets, ObjectMapper json, ComplianceService compliance) {
        this.accounts=accounts; this.methods=methods; this.ads=ads; this.attachments=attachments;
        this.secrets=secrets; this.json=json; this.compliance=compliance;
    }

    List<C2cPaymentAccount> list(long userId) {
        return accounts.byUser(userId);
    }

    C2cPaymentAccount get(long userId, UUID id, boolean requireApproved) {
        var account=accounts.find(userId,id).orElseThrow(()->new IllegalArgumentException("收款账号不存在"));
        if (requireApproved) {
            if (!"APPROVED".equals(account.status())) throw new IllegalStateException("收款账号需先通过实名审核");
            if (methods.available(account.method(),account.fiat()).isEmpty()) throw new IllegalStateException("收款方式当前不可用");
        }
        return account;
    }

    @Transactional
    C2cPaymentAccount save(long userId, UUID id, long revision, String fiat, String method, Details details) {
        validateDetails(userId,fiat,method,details);
        var envelope = new LinkedHashMap<String,Object>();
        envelope.put("purpose","C2C_PAYMENT"); envelope.put("userId",Long.toString(userId));
        envelope.put("fiat",fiat); envelope.put("methodCode",method);
        envelope.put("holderName",Normalizer.normalize(details.holderName().trim(),Normalizer.Form.NFKC));
        envelope.put("accountNumber",details.accountNumber().trim());
        envelope.put("bankName",details.bankName()==null?"":details.bankName().trim());
        envelope.put("qrAttachmentId",details.qrAttachmentId()==null?null:details.qrAttachmentId().toString());
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
            if(changed!=1) throw new IllegalStateException("收款账号已变更，请刷新");
        }
        return get(userId,id,false);
    }

    @Transactional
    void disable(long userId,UUID id,long revision) {
        pauseAds(userId,id);
        int changed=accounts.disable(id,userId,revision);
        if(changed!=1) throw new IllegalStateException("收款账号已变更，请刷新");
    }

    @Transactional
    C2cPaymentAccount review(UUID id,long revision,boolean approved,String reason,JwtPrincipal administrator) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("请填写实名核验结论");
        var value=accounts.lock(id).orElseThrow(()->new IllegalArgumentException("收款账号不存在"));
        if(value.userId()==administrator.userId()) throw new IllegalStateException("不能审核本人的收款账号");
        if(!"PENDING".equals(value.status()) || value.revision()!=revision) throw new IllegalStateException("收款审核状态已变更");
        if(approved) {
            var kyc=compliance.kyc(value.userId());
            if(kyc==null || !"VERIFIED".equals(kyc.status()) || (kyc.expiresAt()!=null && !kyc.expiresAt().isAfter(Instant.now())))
                throw new IllegalStateException("账号本人尚无有效身份认证");
            var verified=details(value);
            Object qr=verified.get("qrAttachmentId");
            validateDetails(value.userId(),value.fiat(),value.method(),new Details((String)verified.get("holderName"),
                    (String)verified.get("accountNumber"),(String)verified.get("bankName"),qr==null?null:UUID.fromString(qr.toString())));
        }
        accounts.review(id,approved?"APPROVED":"REJECTED",reason.trim(),administrator.userId());
        return get(value.userId(),id,false);
    }

    Map<String,Object> details(C2cPaymentAccount account) {
        var result = details(account.userId(), account.ciphertext());
        if (!account.fiat().equals(result.get("fiat")) || !account.method().equals(result.get("methodCode")))
            throw new IllegalStateException("收款方式与资料不匹配");
        return result;
    }

    Map<String,Object> details(long ownerUserId, String ciphertext) {
        var result=json.readValue(secrets.decryptSecret(ciphertext),Map.class);
        if(!"C2C_PAYMENT".equals(result.get("purpose")) || !Long.toString(ownerUserId).equals(result.get("userId")))
            throw new IllegalStateException("收款资料归属不匹配");
        var safe=new LinkedHashMap<String,Object>();
        for(String field:List.of("fiat","methodCode","holderName","accountNumber","bankName","qrAttachmentId")) safe.put(field,result.get(field));
        return safe;
    }

    private void validateDetails(long userId,String fiat,String method,Details details) {
        if(userId<=0 || fiat==null || !fiat.matches("[A-Z]{3,8}") || method==null || details==null
                || details.holderName()==null || details.holderName().trim().length()<2 || details.holderName().length()>80
                || details.accountNumber()==null || details.accountNumber().trim().length()<3 || details.accountNumber().length()>128
                || (details.bankName()!=null && details.bankName().length()>120)) throw new IllegalArgumentException("收款资料不完整");
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
