package com.surprising.gateway.c2c;

import jakarta.servlet.http.HttpServletRequest;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import tools.jackson.databind.ObjectMapper;

@RestController
@RequestMapping("/api/v1/admin/c2c/orders/{order}/chat")
@ConditionalOnProperty(name="surprising.c2c.enabled",havingValue="true")
public class C2cAdminChatController {
    private final C2cAdminAccess access;
    private final C2cAdminChat chat;
    private final ObjectMapper json;
    public C2cAdminChatController(C2cAdminAccess access,C2cAdminChat chat,ObjectMapper json) {this.access=access;this.chat=chat;this.json=json;}
    @GetMapping
    public Map<String,Object> history(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID order,
            @RequestParam(defaultValue="0") String after,@RequestParam(required=false) String before,
            @RequestParam(defaultValue="0") String afterEvent,@RequestParam(defaultValue="50") int limit,
            @RequestParam(defaultValue="false") boolean latest,HttpServletRequest request) {
        if(latest) {
            if(!"0".equals(after) || before!=null || !"0".equals(afterEvent)) throw new IllegalArgumentException("最近消息不能混用其他游标");
            return chat.latest(auth,access.request(request),order,limit);
        }
        return chat.history(auth,access.request(request),order,C2cMessages.cursor(after),before==null?null:C2cMessages.cursor(before),C2cMessages.cursor(afterEvent),limit);
    }
    @GetMapping("/requests/{messageRequest}")
    public Map<String,Object> receipt(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID order,
            @PathVariable UUID messageRequest,HttpServletRequest request) {
        return chat.receipt(auth,access.request(request),order,messageRequest);
    }
    @GetMapping("/attachments/{attachment}")
    public ResponseEntity<byte[]> attachment(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID order,
            @PathVariable UUID attachment,HttpServletRequest request) {
        return C2cChatController.file(chat.attachment(auth,access.request(request),order,attachment));
    }
    @PostMapping
    public Map<String,Object> reply(@RequestHeader(value="Authorization",required=false) String auth,@PathVariable UUID order,
            @RequestBody C2cChatController.Send body,HttpServletRequest request) {
        return chat.reply(auth,access.request(request),order,body,json.writeValueAsBytes(body));
    }
}
