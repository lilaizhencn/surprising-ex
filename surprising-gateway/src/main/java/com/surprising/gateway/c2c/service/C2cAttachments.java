package com.surprising.gateway.c2c.service;

import com.surprising.gateway.c2c.exception.C2cProblem;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;

import com.surprising.gateway.c2c.model.C2cOrder;
import com.surprising.gateway.c2c.model.C2cAttachment;
import com.surprising.gateway.c2c.model.C2cAttachmentContent;
import com.surprising.gateway.c2c.model.C2cConversation;
import com.surprising.gateway.c2c.repository.C2cAttachmentRepository;
import com.surprising.gateway.c2c.repository.C2cMessageRepository;
import com.surprising.gateway.c2c.utils.C2cDigests;
import com.surprising.gateway.provider.auth.GatewayUserRepository;
import com.surprising.gateway.provider.service.KycDocumentStorageException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.imageio.ImageIO;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 私有文件先登记固定内容身份，再写存储，核验完整字节后才 READY；重试不换文件或用途。 */
@Service
public class C2cAttachments {
    private final C2cAttachmentRepository attachments;
    private final C2cMessageRepository messages;
    private final GatewayUserRepository users;
    private final C2cConversations conversations;
    private final C2cOrdersRepository orders;
    private final C2cPaymentAccounts payments;
    private final C2cR2Storage storage;
    private final TransactionTemplate transactions;
    public C2cAttachments(C2cAttachmentRepository attachments,C2cMessageRepository messages,GatewayUserRepository users,
                          C2cConversations conversations,C2cOrdersRepository orders,C2cPaymentAccounts payments,
                          C2cR2Storage storage,PlatformTransactionManager transactions) {
        this.attachments=attachments;this.messages=messages;this.users=users;this.conversations=conversations;
        this.orders=orders;this.payments=payments;this.storage=storage;this.transactions=new TransactionTemplate(transactions);
    }

    public Map<String,Object> upload(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,byte[] bytes) {
        storage.ensureEnabled();
        validateBytes(mime,bytes,"PAYMENT_QR".equals(purpose));
        C2cAttachment admitted=admit(user,id,purpose,order,conversation,name,mime,bytes.length,C2cDigests.sha256(bytes));
        if("READY".equals(admitted.status())) return view(admitted);
        try {storage.store(admitted.key(),bytes,mime);}
        catch(KycDocumentStorageException uncertain) {
            // 首次写入也可能已成功。只核对原对象，不能覆盖未知结果或删除另一并发请求的文件。
            return confirm(user,id);
        }
        return confirm(user,id);
    }

    public Map<String,Object> presign(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,int bytes,String hash) {
        storage.ensureEnabled();
        var admitted=admit(user,id,purpose,order,conversation,name,mime,bytes,hash);
        var result=view(admitted);
        if(!"READY".equals(admitted.status())) result.putAll(storage.presign(admitted.key(),admitted.mime(),admitted.bytes()));
        return result;
    }
    public Map<String,Object> confirm(long user,UUID id) {
        var value=attachments.find(id).orElse(null);
        if(value==null) throw new IllegalArgumentException("上传请求不存在");
        if(value.user()!=user) throw C2cProblem.forbidden("无权访问此文件");
        if("READY".equals(value.status())) return view(value);
        byte[] bytes=storage.read(value.key());
        if(!matches(value,bytes)) throw C2cProblem.unavailable("文件核验未完成，请使用原上传请求查询结果");
        validateBytes(value.mime(),bytes,"PAYMENT_QR".equals(value.purpose()));
        attachments.markReady(id);
        return view(attachments.find(id).orElseThrow());
    }
    private C2cAttachment admit(long user,UUID id,String purpose,UUID order,UUID conversation,String name,String mime,int bytes,String hash) {
        String safeName=name;
        return transactions.execute(status->{
            if(!users.lockForNoKeyUpdate(user)) throw new IllegalStateException("账户不可用");
            var previous=attachments.find(id).orElse(null);
            if(previous!=null) {
                if(previous.user()!=user) throw C2cProblem.forbidden("无权访问此文件");
                if(!Objects.equals(order,previous.order()) || !Objects.equals(conversation,previous.conversation())
                        || !purpose.equals(previous.purpose()) || !safeName.equals(previous.name())
                        || !mime.equals(previous.mime()) || bytes!=previous.bytes() || !hash.equals(previous.sha256()))
                    throw new IllegalStateException("上传请求已用于其他文件，请保留原请求查询结果");
                return previous;
            }
            scope(user,purpose,order,conversation);
            var usage=attachments.usage(user);
            if(usage.files()>=100 || usage.bytes()+bytes>104857600) throw new C2cProblem(org.springframework.http.HttpStatus.TOO_MANY_REQUESTS,
                    "C2C_UPLOAD_LIMIT","文件上传已达限额");
            String key="c2c/"+user+"/"+id;
            attachments.insert(id,user,order,conversation,purpose,key,mime,bytes,hash,safeName);
            return attachments.find(id).orElseThrow();
        });
    }

    public Map<String,Object> own(long user,UUID id) {
        var attachment=attachments.find(id).orElse(null);
        if(attachment==null) return Map.of("attachmentId",id.toString(),"status","UNKNOWN");
        if(user!=attachment.user()) throw C2cProblem.forbidden("无权访问此文件");
        return view(attachment);
    }
    public C2cAttachmentContent download(long user,UUID id,UUID orderContext) {
        var attachment=ready(id);authorize(user,attachment,orderContext);
        return content(attachment);
    }
    public C2cAttachmentContent adminDownload(UUID id,UUID orderContext) {
        if(orderContext==null) throw new IllegalArgumentException("请选择证据所属订单");
        var attachment=ready(id);var order=orders.get(orderContext,false);
        boolean belongs=orderContext.equals(attachment.order());
        if("PAYMENT_QR".equals(attachment.purpose())) belongs=qrMatches(attachment,order);
        if(!belongs) throw C2cProblem.forbidden("文件不属于此订单");
        return content(attachment);
    }
    public C2cAttachment message(long user,UUID id,C2cConversation conversation) {
        var value=ready(id);
        if(user!=value.user() || !"MESSAGE".equals(value.purpose()) || !conversation.id().equals(value.conversation())
                || !Objects.equals(conversation.orderId(),value.order())) throw C2cProblem.forbidden("附件不属于此会话或发送者");
        return value;
    }
    public Map<String,Object> view(C2cAttachment value) {
        var result=new LinkedHashMap<String,Object>();
        result.put("attachmentId",value.id().toString());result.put("purpose",value.purpose());result.put("status",value.status());
        result.put("contentType",value.mime());result.put("byteCount",value.bytes());result.put("name",value.name());
        return result;
    }
    public C2cAttachment ready(UUID id) {
        var value=attachments.find(id).orElse(null);
        if(value==null || !"READY".equals(value.status())) throw new IllegalArgumentException("文件尚不可用");
        return value;
    }
    private void scope(long user,String purpose,UUID order,UUID conversation) {
        if("PAYMENT_QR".equals(purpose)) {
            return;
        } else if("PROOF".equals(purpose)) {
            C2cOrders.requireParticipant(user,orders.get(order,false));
        } else {
            var value=conversations.get(user,conversation,false);conversations.requireSend(user,value);
            if(!Objects.equals(order,value.orderId())) throw new IllegalArgumentException("附件订单与会话不匹配");
        }
    }
    private void authorize(long user,C2cAttachment attachment,UUID orderContext) {
        if(user==attachment.user()) return;
        if("PROOF".equals(attachment.purpose())) {C2cOrders.requireParticipant(user,orders.get(attachment.order(),false));return;}
        if("MESSAGE".equals(attachment.purpose())) {
            conversations.get(user,attachment.conversation(),false);
            if(!messages.referencesAttachment(attachment.id())) throw C2cProblem.forbidden("消息尚未发送");
            return;
        }
        if(orderContext==null) throw C2cProblem.forbidden("请从订单访问收款码");
        var order=orders.get(orderContext,false);C2cOrders.requireParticipant(user,order);
        if(!qrMatches(attachment,order)) throw C2cProblem.forbidden("收款码与订单快照不匹配");
    }
    private boolean qrMatches(C2cAttachment attachment,C2cOrder order) {
        return attachment.user()==order.sellerUserId() && attachment.id().equals(
                payments.details(order.sellerUserId(),order.paymentCiphertext()).qrAttachmentId());
    }
    private C2cAttachmentContent content(C2cAttachment attachment) {
        byte[] bytes=storage.read(attachment.key());
        if(!matches(attachment,bytes)) throw C2cProblem.unavailable("文件暂不可用");
        String suffix=switch(attachment.mime()) {case "image/jpeg"->"jpg";case "image/png"->"png";default->"pdf";};
        return new C2cAttachmentContent(bytes,attachment.mime(),"c2c-"+attachment.id()+"."+suffix);
    }
    public Map<UUID,C2cAttachment> readyByIds(Collection<UUID> ids) {
        return attachments.readyByIds(ids).stream().collect(java.util.stream.Collectors.toUnmodifiableMap(C2cAttachment::id,value -> value));
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
    private static boolean matches(C2cAttachment attachment,byte[] bytes) {return bytes.length==attachment.bytes() && C2cDigests.sha256(bytes).equals(attachment.sha256());}
}
