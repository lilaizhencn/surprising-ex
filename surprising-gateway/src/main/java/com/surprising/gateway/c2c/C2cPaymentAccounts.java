package com.surprising.gateway.c2c;

import com.surprising.gateway.provider.auth.AuthModels.JwtPrincipal;
import com.surprising.gateway.provider.auth.ComplianceService;
import com.surprising.gateway.provider.auth.TotpService;
import java.text.Normalizer;
import java.time.Instant;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** 收款账号仅本人/订单双方/有权限客服可见；变更需重审，在途订单仍使用原快照。 */
@Service
public class C2cPaymentAccounts {
    record Details(String holderName, String accountNumber, String bankName, UUID qrAttachmentId) { }
    record Account(UUID id, long userId, String fiat, String method, String ciphertext, String maskedLabel,
                   String status, long revision) { }
    private final JdbcTemplate jdbc;
    private final TotpService secrets;
    private final ObjectMapper json;
    private final ComplianceService compliance;
    public C2cPaymentAccounts(JdbcTemplate jdbc, TotpService secrets, ObjectMapper json, ComplianceService compliance) {
        this.jdbc=jdbc; this.secrets=secrets; this.json=json; this.compliance=compliance;
    }

    List<Account> list(long userId) {
        return jdbc.query("SELECT * FROM c2c_payment_accounts WHERE user_id=? ORDER BY created_at DESC,account_id", this::account, userId);
    }

    Account get(long userId, UUID id, boolean requireApproved) {
        var rows = jdbc.query("SELECT * FROM c2c_payment_accounts WHERE user_id=? AND account_id=?",this::account,userId,id);
        if (rows.isEmpty()) throw new IllegalArgumentException("收款账号不存在");
        var account=rows.getFirst();
        if (requireApproved) {
            if (!"APPROVED".equals(account.status())) throw new IllegalStateException("收款账号需先通过实名审核");
            if (!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_payment_methods WHERE code=? AND enabled AND ?=ANY(currencies))",
                    Boolean.class,account.method(),account.fiat()))) throw new IllegalStateException("收款方式当前不可用");
        }
        return account;
    }

    @Transactional
    Account save(long userId, UUID id, long revision, String fiat, String method, Details details) {
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
            jdbc.update("""
                    INSERT INTO c2c_payment_accounts(account_id,user_id,fiat,method_code,details_ciphertext,masked_label)
                    VALUES (?,?,?,?,?,?)
                    """,id,userId,fiat,method,encrypted,masked);
        } else {
            pauseAds(userId,id);
            int changed=jdbc.update("""
                    UPDATE c2c_payment_accounts SET fiat=?,method_code=?,details_ciphertext=?,masked_label=?,
                        status='PENDING',reviewed_by=NULL,reviewed_at=NULL,review_reason=NULL,revision=revision+1,updated_at=now()
                    WHERE account_id=? AND user_id=? AND revision=?
                    """,fiat,method,encrypted,masked,id,userId,revision);
            if(changed!=1) throw new IllegalStateException("收款账号已变更，请刷新");
        }
        return get(userId,id,false);
    }

    @Transactional
    void disable(long userId,UUID id,long revision) {
        pauseAds(userId,id);
        int changed=jdbc.update("""
                UPDATE c2c_payment_accounts SET status='DISABLED',revision=revision+1,updated_at=now()
                WHERE account_id=? AND user_id=? AND revision=?
                """,id,userId,revision);
        if(changed!=1) throw new IllegalStateException("收款账号已变更，请刷新");
    }

    @Transactional
    Account review(UUID id,long revision,boolean approved,String reason,JwtPrincipal administrator) {
        if(reason==null || reason.isBlank() || reason.length()>1000) throw new IllegalArgumentException("请填写实名核验结论");
        var rows=jdbc.query("SELECT * FROM c2c_payment_accounts WHERE account_id=? FOR UPDATE",this::account,id);
        if(rows.isEmpty()) throw new IllegalArgumentException("收款账号不存在");
        var value=rows.getFirst();
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
        jdbc.update("""
                UPDATE c2c_payment_accounts SET status=?,review_reason=?,reviewed_by=?,reviewed_at=now(),
                    revision=revision+1,updated_at=now() WHERE account_id=?
                """,approved?"APPROVED":"REJECTED",reason.trim(),administrator.userId(),id);
        return get(value.userId(),id,false);
    }

    Map<String,Object> details(Account account) {
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
        var methods=jdbc.query("SELECT requires_bank_name,supports_qr FROM c2c_payment_methods WHERE code=? AND enabled AND ?=ANY(currencies)",
                (r,n)->Map.of("bank",r.getBoolean(1),"qr",r.getBoolean(2)),method,fiat);
        if(methods.isEmpty()) throw new IllegalArgumentException("该币种收款方式尚未开放");
        if(methods.getFirst().get("bank") && (details.bankName()==null || details.bankName().isBlank()
                || !details.accountNumber().trim().matches("[0-9]{8,40}"))) throw new IllegalArgumentException("请填写正确的银行名称和卡号");
        if(details.qrAttachmentId()!=null && (!methods.getFirst().get("qr") || !Boolean.TRUE.equals(jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM c2c_attachments WHERE attachment_id=? AND user_id=? AND purpose='PAYMENT_QR')
                """,Boolean.class,details.qrAttachmentId(),userId)))) throw new IllegalArgumentException("收款码归属不匹配");
    }

    private void pauseAds(long userId,UUID accountId) {
        // 与下单相同：先锁广告，再变更收款账号，阻止旧报价使用新账号。
        jdbc.update("""
                UPDATE c2c_ads SET status='PAUSED',revision=revision+1,updated_at=now()
                WHERE maker_user_id=? AND status='ACTIVE' AND ?=ANY(payment_account_ids)
                """,userId,accountId);
    }

    private Account account(java.sql.ResultSet r,int n) throws java.sql.SQLException {
        return new Account(r.getObject("account_id",UUID.class),r.getLong("user_id"),r.getString("fiat"),r.getString("method_code"),
                r.getString("details_ciphertext"),r.getString("masked_label"),r.getString("status"),r.getLong("revision"));
    }
}
