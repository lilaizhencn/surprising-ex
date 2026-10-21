package com.surprising.gateway.c2c.controller;

import com.surprising.gateway.c2c.service.C2cAccess;
import com.surprising.gateway.c2c.service.C2cAttachments;
import com.surprising.gateway.c2c.service.C2cConversations;
import com.surprising.gateway.c2c.service.C2cMessages;

import java.io.IOException;
import com.surprising.gateway.c2c.model.C2cChatViews;
import com.surprising.gateway.c2c.model.C2cAttachmentContent;
import com.surprising.gateway.c2c.utils.C2cChatCursors;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

@RestController
@RequestMapping("/api/v1/c2c")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cChatController {
    public record Start(UUID orderId,Long targetUserId) { }
    public record Send(UUID requestId,String text,UUID attachmentId) { }
    public record Read(String messageId,String eventId) { }
    public record Upload(String purpose,UUID orderId,UUID conversationId,String name,String contentType,int byteCount,String sha256) { }
    private final C2cAccess access;
    private final C2cConversations conversations;
    private final C2cMessages messages;
    private final C2cAttachments attachments;
    public C2cChatController(C2cAccess access,C2cConversations conversations,C2cMessages messages,C2cAttachments attachments) {
        this.access=access;this.conversations=conversations;this.messages=messages;this.attachments=attachments;
    }
    @PostMapping("/conversations")
    public Map<String,Object> start(@RequestHeader(value="Authorization",required=false) String auth,@RequestBody Start request) {
        var user=access.user(auth);
        if((request.orderId()==null)==(request.targetUserId()==null) || (request.targetUserId()!=null && request.targetUserId()<=0))
            throw new IllegalArgumentException("请选择订单或咨询对象");
        return conversations.start(user.userId(),request.orderId(),request.targetUserId());
    }
    @GetMapping("/conversations")
    public Map<String,Object> list(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset) {
        var user=access.user(auth);
        if(limit<1 || limit>50 || offset<0 || offset>10000 || search==null || search.length()>80) throw new IllegalArgumentException("会话查询条件无效");
        return conversations.list(user.userId(),search,limit,offset);
    }
    @GetMapping("/conversations/{id}/messages")
    public C2cChatViews.Page page(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,
            @RequestParam(defaultValue="0") String after,@RequestParam(required=false) String before,
            @RequestParam(defaultValue="0") String afterEvent,@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="false") boolean latest) {
        var user=access.user(auth);
        pageRange(0,null,0,limit);
        if(latest) {
            if(!"0".equals(after) || before!=null || !"0".equals(afterEvent)) throw new IllegalArgumentException("最近消息不能混用其他游标");
            return messages.latest(user.userId(),id,limit);
        }
        long a=C2cChatCursors.parse(after),e=C2cChatCursors.parse(afterEvent);Long older=before==null?null:C2cChatCursors.parse(before);
        pageRange(a,older,e,limit);return messages.page(user.userId(),id,a,older,e,limit);
    }
    @PostMapping("/conversations/{id}/messages")
    public C2cChatViews.Message send(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Send request) {
        var user=access.user(auth);String text=messageText(request.text());
        requireMessage(request.requestId(),text,request.attachmentId());return messages.send(user.userId(),id,request.requestId(),text,request.attachmentId());
    }
    @GetMapping("/conversations/{id}/requests/{request}")
    public Map<String,Object> receipt(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@PathVariable UUID request) {
        return messages.receipt(access.user(auth).userId(),id,request);
    }
    @PostMapping("/conversations/{id}/read")
    public Map<String,Object> read(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Read request) {
        return messages.markRead(access.user(auth).userId(),id,C2cChatCursors.parse(request.messageId()),C2cChatCursors.parse(request.eventId()));
    }
    @PostMapping(value="/attachments/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,
            @RequestParam String purpose,@RequestParam(required=false) UUID orderId,@RequestParam(required=false) UUID conversationId,
            @RequestParam MultipartFile file) throws IOException {
        var user=access.user(auth);
        if(file.getSize()>10485760) throw new IllegalArgumentException("请选择 10MB 以内的文件");
        byte[] bytes=file.getBytes();String name=fileName(file.getOriginalFilename());
        uploadMetadata(id,purpose,orderId,conversationId,file.getContentType(),bytes.length);
        return attachments.upload(user.userId(),id,purpose,orderId,conversationId,name,file.getContentType(),bytes);
    }
    @GetMapping("/attachments/{id}")
    public Map<String,Object> attachment(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id) {
        return attachments.own(access.user(auth).userId(),id);
    }
    @PostMapping("/attachments/{id}/presigned-put")
    public Map<String,Object> presign(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Upload request) {
        var user=access.user(auth);String name=fileName(request.name());
        uploadMetadata(id,request.purpose(),request.orderId(),request.conversationId(),request.contentType(),request.byteCount());
        if(request.sha256()==null || !request.sha256().matches("[a-f0-9]{64}")) throw new IllegalArgumentException("文件内容摘要无效");
        return attachments.presign(user.userId(),id,request.purpose(),request.orderId(),request.conversationId(),name,request.contentType(),request.byteCount(),request.sha256());
    }
    @PostMapping("/attachments/{id}/confirm")
    public Map<String,Object> confirm(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id) {
        return attachments.confirm(access.user(auth).userId(),id);
    }
    @GetMapping("/attachments/{id}/content")
    public ResponseEntity<byte[]> content(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,
            @RequestParam(required=false) UUID orderId) {
        return file(attachments.download(access.user(auth).userId(),id,orderId));
    }
    public static String messageText(String raw) {
        String text=raw==null?"":raw.replace("\r\n","\n").strip();
        if(text.length()>4000 || text.codePoints().anyMatch(c -> Character.isISOControl(c) && c!='\n' && c!='\t'))
            throw new IllegalArgumentException("消息内容无效或超过 4000 字");
        return text;
    }
    public static void requireMessage(UUID request,String text,UUID attachment) {
        if(request==null || (text.isBlank() && attachment==null)) throw new IllegalArgumentException("请输入消息或选择附件");
    }
    public static void pageRange(long after,Long before,long event,int limit) {
        if(after<0 || event<0 || (before!=null && (before<1 || after!=0)) || limit<1 || limit>50)
            throw new IllegalArgumentException("消息查询条件无效");
    }
    private static String fileName(String name) {
        if(name==null || name.isBlank() || name.length()>160 || name.indexOf('/')>=0 || name.indexOf('\\')>=0
                || name.codePoints().anyMatch(Character::isISOControl)) throw new IllegalArgumentException("文件名无效");
        return name.trim();
    }
    private static void uploadMetadata(UUID id,String purpose,UUID order,UUID conversation,String mime,int bytes) {
        if(id==null || purpose==null || !Set.of("PAYMENT_QR","PROOF","MESSAGE").contains(purpose)
                || mime==null || !Set.of("image/jpeg","image/png","application/pdf").contains(mime)
                || ("PAYMENT_QR".equals(purpose) && "application/pdf".equals(mime)) || bytes<8 || bytes>10485760)
            throw new IllegalArgumentException("文件格式、大小或用途无效");
        if("PAYMENT_QR".equals(purpose) && (order!=null || conversation!=null)) throw new IllegalArgumentException("收款码不能绑定其他用途");
        if("PROOF".equals(purpose) && (order==null || conversation!=null)) throw new IllegalArgumentException("付款凭证必须绑定订单");
        if("MESSAGE".equals(purpose) && conversation==null) throw new IllegalArgumentException("消息附件必须绑定会话");
    }
    public static ResponseEntity<byte[]> file(C2cAttachmentContent content) {
        boolean image=content.mime().startsWith("image/");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","sandbox; default-src 'none'")
                .header("Content-Disposition",(image?"inline":"attachment")+"; filename=\""+content.filename()+"\"")
                .contentType(MediaType.parseMediaType(content.mime())).body(content.bytes());
    }
}
