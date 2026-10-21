package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.provider.service.KycDocumentStorageException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 私有文件先登记固定内容身份，再写存储，核验完整字节后才 READY；重试不换文件或用途。 */
@Service
public class C2cAttachments {
    record Attachment(UUID id,long user,UUID order,UUID conversation,String purpose,String key,String mime,
                      int bytes,String sha256,String name,String status) { }
    record Content(byte[] bytes,String mime,String filename) { }
    private final JdbcTemplate jdbc;
    private final C2cConversations conversations;
    private final C2cOrdersRepository orders;
    private final C2cPaymentAccounts payments;
    private final C2cR2Storage storage;
    private final TransactionTemplate transactions;
    public C2cAttachments(JdbcTemplate jdbc,C2cConversations conversations,C2cOrdersRepository orders,
                          C2cPaymentAccounts payments,C2cR2Storage storage,PlatformTransactionManager transactions) {
        this.jdbc=jdbc;this.conversations=conversations;this.orders=orders;this.payments=payments;
        this.storage=storage;this.transactions=new TransactionTemplate(transactions);
    }

    Map<String,Object> upload(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,byte[] bytes) {
        storage.ensureEnabled();
        validateBytes(mime,bytes,"PAYMENT_QR".equals(purpose));
        Attachment admitted=admit(user,id,purpose,order,conversation,name,mime,bytes.length,sha256(bytes));
        if("READY".equals(admitted.status())) return view(admitted);
        try {storage.store(admitted.key(),bytes,mime);}
        catch(KycDocumentStorageException uncertain) {
            // 首次写入也可能已成功。只核对原对象，不能覆盖未知结果或删除另一并发请求的文件。
            return confirm(user,id);
        }
        return confirm(user,id);
    }

    Map<String,Object> presign(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,int bytes,String hash) {
        storage.ensureEnabled();
        var admitted=admit(user,id,purpose,order,conversation,name,mime,bytes,hash);
        var result=view(admitted);
        if(!"READY".equals(admitted.status())) result.putAll(storage.presign(admitted.key(),admitted.mime(),admitted.bytes()));
        return result;
    }
    Map<String,Object> confirm(long user,UUID id) {
        var value=find(id);
        if(value==null) throw new IllegalArgumentException("上传请求不存在");
        if(value.user()!=user) throw C2cProblem.forbidden("无权访问此文件");
        if("READY".equals(value.status())) return view(value);
        byte[] bytes=storage.read(value.key());
        if(!matches(value,bytes)) throw C2cProblem.unavailable("文件核验未完成，请使用原上传请求查询结果");
        validateBytes(value.mime(),bytes,"PAYMENT_QR".equals(value.purpose()));
        jdbc.update("UPDATE c2c_attachments SET status='READY' WHERE attachment_id=? AND status='UPLOADING'",id);
        return view(find(id));
    }
    private Attachment admit(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,int bytes,String hash) {
        if(id==null || purpose==null || !Set.of("PAYMENT_QR","PROOF","MESSAGE").contains(purpose)
                || mime==null || !Set.of("image/jpeg","image/png","application/pdf").contains(mime)
                || ("PAYMENT_QR".equals(purpose) && "application/pdf".equals(mime)) || bytes<8 || bytes>10485760
                || hash==null || !hash.matches("[a-f0-9]{64}")) throw new IllegalArgumentException("文件格式、大小或用途无效");
        String safeName=safeName(name);
        return transactions.execute(status->{
            jdbc.queryForObject("SELECT user_id FROM gateway_users WHERE user_id=? FOR NO KEY UPDATE",Long.class,user);
            var previous=find(id);
            if(previous!=null) {
                if(previous.user()!=user) throw C2cProblem.forbidden("无权访问此文件");
                if(!Objects.equals(order,previous.order()) || !Objects.equals(conversation,previous.conversation())
                        || !purpose.equals(previous.purpose()) || !safeName.equals(previous.name())
                        || !mime.equals(previous.mime()) || bytes!=previous.bytes() || !hash.equals(previous.sha256()))
                    throw new IllegalStateException("上传请求已用于其他文件，请保留原请求查询结果");
                return previous;
            }
            scope(user,purpose,order,conversation);
            if(Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT count(*) FILTER(WHERE created_at>now()-interval '24 hours')>=100
                        OR COALESCE(sum(byte_count) FILTER(WHERE created_at>now()-interval '24 hours'),0)+?>104857600 FROM c2c_attachments WHERE user_id=?
                    """,Boolean.class,bytes,user))) throw new C2cProblem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                            "C2C_UPLOAD_LIMIT","文件上传已达限额");
            String key="c2c/"+user+"/"+id;
            jdbc.update("""
                    INSERT INTO c2c_attachments(attachment_id,user_id,order_id,conversation_id,purpose,storage_key,
                        content_type,byte_count,sha256,original_name) VALUES(?,?,?,?,?,?,?,?,?,?)
                    """,id,user,order,conversation,purpose,key,mime,bytes,hash,safeName);
            return find(id);
        });
    }

    Map<String,Object> own(long user,UUID id) {
        var attachment=find(id);
        if(attachment==null) return Map.of("attachmentId",id.toString(),"status","UNKNOWN");
        if(user!=attachment.user()) throw C2cProblem.forbidden("无权访问此文件");
        return view(attachment);
    }
    Content download(long user,UUID id,UUID orderContext) {
        var attachment=ready(id);authorize(user,attachment,orderContext);
        return content(attachment);
    }
    Content adminDownload(UUID id,UUID orderContext) {
        if(orderContext==null) throw new IllegalArgumentException("请选择证据所属订单");
        var attachment=ready(id);var order=orders.get(orderContext,false);
        boolean belongs=orderContext.equals(attachment.order());
        if("PAYMENT_QR".equals(attachment.purpose())) belongs=qrMatches(attachment,order);
        if(!belongs) throw C2cProblem.forbidden("文件不属于此订单");
        return content(attachment);
    }
    Attachment message(long user,UUID id,C2cConversations.Conversation conversation) {
        var value=ready(id);
        if(user!=value.user() || !"MESSAGE".equals(value.purpose()) || !conversation.id().equals(value.conversation())
                || !Objects.equals(conversation.orderId(),value.order())) throw C2cProblem.forbidden("附件不属于此会话或发送者");
        return value;
    }
    Map<String,Object> view(Attachment value) {
        var result=new LinkedHashMap<String,Object>();
        result.put("attachmentId",value.id().toString());result.put("purpose",value.purpose());result.put("status",value.status());
        result.put("contentType",value.mime());result.put("byteCount",value.bytes());result.put("name",value.name());
        return result;
    }
    Attachment ready(UUID id) {
        var value=find(id);
        if(value==null || !"READY".equals(value.status())) throw new IllegalArgumentException("文件尚不可用");
        return value;
    }
    private void scope(long user,String purpose,UUID order,UUID conversation) {
        if("PAYMENT_QR".equals(purpose)) {
            if(order!=null || conversation!=null) throw new IllegalArgumentException("收款码不能绑定其他用途");
        } else if("PROOF".equals(purpose)) {
            if(order==null || conversation!=null) throw new IllegalArgumentException("付款凭证必须绑定订单");
            C2cOrders.requireParticipant(user,orders.get(order,false));
        } else {
            if(conversation==null) throw new IllegalArgumentException("消息附件必须绑定会话");
            var value=conversations.get(user,conversation,false);conversations.requireSend(user,value);
            if(!Objects.equals(order,value.orderId())) throw new IllegalArgumentException("附件订单与会话不匹配");
        }
    }
    private void authorize(long user,Attachment attachment,UUID orderContext) {
        if(user==attachment.user()) return;
        if("PROOF".equals(attachment.purpose())) {C2cOrders.requireParticipant(user,orders.get(attachment.order(),false));return;}
        if("MESSAGE".equals(attachment.purpose())) {
            conversations.get(user,attachment.conversation(),false);
            if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM c2c_messages WHERE attachment_id=?)",
                    Boolean.class,attachment.id()))) throw C2cProblem.forbidden("消息尚未发送");
            return;
        }
        if(orderContext==null) throw C2cProblem.forbidden("请从订单访问收款码");
        var order=orders.get(orderContext,false);C2cOrders.requireParticipant(user,order);
        if(!qrMatches(attachment,order)) throw C2cProblem.forbidden("收款码与订单快照不匹配");
    }
    private boolean qrMatches(Attachment attachment,C2cOrder order) {
        return attachment.user()==order.sellerUserId() && attachment.id().equals(
                payments.details(order.sellerUserId(),order.paymentCiphertext()).qrAttachmentId());
    }
    private Content content(Attachment attachment) {
        byte[] bytes=storage.read(attachment.key());
        if(!matches(attachment,bytes)) throw C2cProblem.unavailable("文件暂不可用");
        String suffix=switch(attachment.mime()) {case "image/jpeg"->"jpg";case "image/png"->"png";default->"pdf";};
        return new Content(bytes,attachment.mime(),"c2c-"+attachment.id()+"."+suffix);
    }
    private Attachment find(UUID id) {
        return jdbc.query("SELECT * FROM c2c_attachments WHERE attachment_id=?",(r,n)->new Attachment(id,r.getLong("user_id"),
                r.getObject("order_id",UUID.class),r.getObject("conversation_id",UUID.class),r.getString("purpose"),r.getString("storage_key"),
                r.getString("content_type"),r.getInt("byte_count"),r.getString("sha256"),r.getString("original_name"),r.getString("status")),id)
                .stream().findFirst().orElse(null);
    }
    private static String safeName(String name) {
        if(name==null || name.isBlank() || name.length()>160 || name.indexOf('/')>=0 || name.indexOf('\\')>=0
                || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("文件名无效");
        return name.trim();
    }
    private static void validateBytes(String mime,byte[] bytes,boolean qr) {
        if(bytes==null || bytes.length<8 || bytes.length>10485760 || mime==null
                || !Set.of("image/jpeg","image/png","application/pdf").contains(mime)) throw new IllegalArgumentException("请选择 10MB 以内的 JPG、PNG 或 PDF");
        if("application/pdf".equals(mime)) {
            if(qr || !new String(bytes,0,5,StandardCharsets.US_ASCII).equals("%PDF-")) throw new IllegalArgumentException("文件格式与用途不匹配");
            return;
        }
        boolean magic="image/png".equals(mime)?Arrays.equals(Arrays.copyOf(bytes,8),new byte[]{(byte)137,80,78,71,13,10,26,10})
                :(bytes[0]==(byte)255 && bytes[1]==(byte)216 && bytes[2]==(byte)255);
        if(!magic) throw new IllegalArgumentException("图片格式无效");
        try(var input=ImageIO.createImageInputStream(new ByteArrayInputStream(bytes))) {
            var readers=ImageIO.getImageReaders(input);
            if(!readers.hasNext()) throw new IllegalArgumentException("图片格式无效");
            var reader=readers.next();
            try {
                reader.setInput(input);int width=reader.getWidth(0),height=reader.getHeight(0);
                if(width<1 || height<1 || width>16000 || height>16000 || (long)width*height>40000000)
                    throw new IllegalArgumentException("图片尺寸过大");
            } finally {reader.dispose();}
        } catch(java.io.IOException failure) {throw new IllegalArgumentException("图片无法读取");}
    }
    private static boolean matches(Attachment attachment,byte[] bytes) {return bytes.length==attachment.bytes() && sha256(bytes).equals(attachment.sha256());}
    private static String sha256(byte[] bytes) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
        catch(java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
}
