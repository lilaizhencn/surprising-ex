package com.surprising.gateway.c2c;

import java.io.IOException;
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
        var user=access.user(auth);return conversations.start(user.userId(),request.orderId(),request.targetUserId());
    }
    @GetMapping("/conversations")
    public Map<String,Object> list(@RequestHeader(value="Authorization",required=false) String auth,
            @RequestParam(defaultValue="") String search,@RequestParam(defaultValue="30") int limit,@RequestParam(defaultValue="0") int offset) {
        return conversations.list(access.user(auth).userId(),search,limit,offset);
    }
    @GetMapping("/conversations/{id}/messages")
    public Map<String,Object> page(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,
            @RequestParam(defaultValue="0") String after,@RequestParam(required=false) String before,
            @RequestParam(defaultValue="0") String afterEvent,@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="false") boolean latest) {
        var user=access.user(auth);
        if(latest) {
            if(!"0".equals(after) || before!=null || !"0".equals(afterEvent)) throw new IllegalArgumentException("最近消息不能混用其他游标");
            return messages.latest(user.userId(),id,limit);
        }
        return messages.page(user.userId(),id,C2cMessages.cursor(after),before==null?null:C2cMessages.cursor(before),C2cMessages.cursor(afterEvent),limit);
    }
    @PostMapping("/conversations/{id}/messages")
    public Map<String,Object> send(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Send request) {
        return messages.send(access.user(auth).userId(),id,request.requestId(),request.text(),request.attachmentId());
    }
    @GetMapping("/conversations/{id}/requests/{request}")
    public Map<String,Object> receipt(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@PathVariable UUID request) {
        return messages.receipt(access.user(auth).userId(),id,request);
    }
    @PostMapping("/conversations/{id}/read")
    public Map<String,Object> read(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Read request) {
        return messages.markRead(access.user(auth).userId(),id,C2cMessages.cursor(request.messageId()),C2cMessages.cursor(request.eventId()));
    }
    @PostMapping(value="/attachments/{id}",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public Map<String,Object> upload(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,
            @RequestParam String purpose,@RequestParam(required=false) UUID orderId,@RequestParam(required=false) UUID conversationId,
            @RequestParam MultipartFile file) throws IOException {
        var user=access.user(auth);
        if(file.getSize()>10485760) throw new IllegalArgumentException("请选择 10MB 以内的文件");
        return attachments.upload(user.userId(),id,purpose,orderId,conversationId,file.getOriginalFilename(),file.getContentType(),file.getBytes());
    }
    @GetMapping("/attachments/{id}")
    public Map<String,Object> attachment(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id) {
        return attachments.own(access.user(auth).userId(),id);
    }
    @PostMapping("/attachments/{id}/presigned-put")
    public Map<String,Object> presign(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID id,@RequestBody Upload request) {
        return attachments.presign(access.user(auth).userId(),id,request.purpose(),request.orderId(),request.conversationId(),
                request.name(),request.contentType(),request.byteCount(),request.sha256());
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
    static ResponseEntity<byte[]> file(C2cAttachments.Content content) {
        boolean image=content.mime().startsWith("image/");
        return ResponseEntity.ok().header("Cache-Control","no-store").header("X-Content-Type-Options","nosniff")
                .header("Content-Security-Policy","sandbox; default-src 'none'")
                .header("Content-Disposition",(image?"inline":"attachment")+"; filename=\""+content.filename()+"\"")
                .contentType(MediaType.parseMediaType(content.mime())).body(content.bytes());
    }
}
