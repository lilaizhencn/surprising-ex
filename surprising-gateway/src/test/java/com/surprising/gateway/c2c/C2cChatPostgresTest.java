package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.controller.C2cController;
import com.surprising.gateway.c2c.service.C2cAdminAccess;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import java.util.*;
import java.util.concurrent.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cChatPostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {
        fixture=new C2cHttpFixture();fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=true");
    }
    @AfterEach void cleanup() {if(fixture!=null)fixture.close();}
    private UUID direct(long user,long other) throws Exception {
        return UUID.fromString((String)fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",user,
                new C2cChatController.Start(null,other),200).get("conversationId"));
    }
    private Map<String,Object> send(long user,UUID conversation,UUID request,String text,int status) throws Exception {
        return fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+conversation+"/messages",user,
                new C2cChatController.Send(request,text,null),status);
    }
    private UUID order() throws Exception {
        UUID ad=fixture.publish("SELL");
        return UUID.fromString((String)fixture.create(fixture.quote(ad,12,"2"),12,"chat-order-"+UUID.randomUUID(),"123456",200).get("resultId"));
    }
    private UUID orderConversation(UUID order,long user) throws Exception {
        return UUID.fromString((String)fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",user,
                new C2cChatController.Start(order,null),200).get("conversationId"));
    }

    @Test void orderConversationUsesFixedPartiesAndSeparatesRepeatedOrders() throws Exception {
        UUID first=order();UUID conversation=orderConversation(first,12);
        assertThat(orderConversation(first,11)).isEqualTo(conversation);
        UUID second=order();assertThat(orderConversation(second,12)).isNotEqualTo(conversation);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",13,new C2cChatController.Start(first,null),403);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/messages",13,null,403);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,new C2cChatController.Start(first,13L),400);
        assertThatThrownBy(()->fixture.db.jdbc.update("UPDATE c2c_conversations SET high_user_id=13 WHERE conversation_id=?",conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    @Test void lostAckIsRecoveredWithoutResendingAndConflictingReuseIsRejected() throws Exception {
        UUID conversation=direct(12,11),request=UUID.randomUUID();
        var original=send(12,conversation,request,"买家消息",200);
        var recovered=fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/requests/"+request,12,null,200);
        assertThat(((Map<?,?>)recovered.get("message")).get("messageId")).isEqualTo(original.get("messageId"));
        assertThat(send(12,conversation,request,"买家消息",200).get("messageId")).isEqualTo(original.get("messageId"));
        send(12,conversation,request,"更改后的内容",409);send(11,conversation,request,"买家消息",409);
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/requests/"+UUID.randomUUID(),12,null,200).get("status")).isEqualTo("UNKNOWN");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(1);
        verifyNoInteractions(fixture.core);
    }
    @Test void concurrentIdenticalSendsHaveOneCommittedMessage() throws Exception {
        UUID conversation=direct(12,11),request=UUID.randomUUID();var start=new CountDownLatch(1);
        try(var executor=Executors.newFixedThreadPool(2)) {
            var calls=new ArrayList<Future<Map<String,Object>>>();
            for(int i=0;i<2;i++) calls.add(executor.submit(()->{start.await();return send(12,conversation,request,"同一条消息",200);}));
            start.countDown();assertThat(calls.get(0).get(10,TimeUnit.SECONDS).get("messageId"))
                    .isEqualTo(calls.get(1).get(10,TimeUnit.SECONDS).get("messageId"));
        }
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(1);
    }
    @Test void strangerAndBlockPoliciesApplyAtSendButDoNotEraseHistoryOrKnownReceipts() throws Exception {
        UUID conversation=direct(12,11),request=UUID.randomUUID();send(12,conversation,request,"原始消息",200);
        fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=false WHERE user_id=11");
        send(12,conversation,UUID.randomUUID(),"陌生人消息",403);
        fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(11,12,'FOLLOW')");
        send(12,conversation,UUID.randomUUID(),"关注后消息",200);
        fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(11,12,'BLOCK')");
        send(12,conversation,UUID.randomUUID(),"拉黑后消息",403);send(12,conversation,request,"原始消息",200);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/messages",12,null,200);
    }
    @Test void anUninitializedProfileKeepsTheDefaultStrangerPrivacyPolicy() throws Exception {
        fixture.db.jdbc.update("INSERT INTO gateway_users(user_id,username) VALUES(14,'qa_no_profile')");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,new C2cChatController.Start(null,14L),403);
        fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(14,12,'FOLLOW')");
        direct(12,14);
    }
    @Test void ongoingOrderCanCommunicateWhileBlockedAndKeepsAuthoritativeOrderEvents() throws Exception {
        UUID order=order(),conversation=orderConversation(order,12);
        fixture.db.jdbc.update("INSERT INTO c2c_relations(user_id,target_user_id,kind) VALUES(11,12,'BLOCK')");
        send(12,conversation,UUID.randomUUID(),"核实订单付款",200);
        var page=fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/messages",12,null,200);
        assertThat((List<?>)page.get("orderEvents")).hasSize(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isEqualTo(1);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/orders/"+order+"/cancel",12,new C2cController.Reason("取消测试订单"),200);
        send(12,conversation,UUID.randomUUID(),"结束后的消息",403);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/messages",12,null,200).get("orderEvents")).hasSizeGreaterThan(1);
    }
    @Test void historyCursorsAndReadMarkersCannotCrossConversationsAndNeverMoveBackwards() throws Exception {
        UUID conversation=direct(12,11),other=direct(12,13);
        var first=send(11,conversation,UUID.randomUUID(),"第一条",200);
        var second=send(11,conversation,UUID.randomUUID(),"第二条",200);
        var foreign=send(13,other,UUID.randomUUID(),"其他会话",200);
        String base="/api/v1/c2c/conversations/"+conversation;
        var page=fixture.call(HttpMethod.GET,base+"/messages?limit=1",12,null,200);
        assertThat(page.get("hasMore")).isEqualTo(true);
        assertThat(((List<?>)page.get("messages"))).hasSize(1);
        assertThat(((Map<?,?>)page.get("conversation")).get("unreadCount")).isEqualTo("2");
        fixture.call(HttpMethod.POST,base+"/read",12,new C2cChatController.Read((String)second.get("messageId"),"0"),200);
        fixture.call(HttpMethod.POST,base+"/read",12,new C2cChatController.Read((String)first.get("messageId"),"0"),200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT last_message_id FROM c2c_message_reads WHERE conversation_id=? AND user_id=12",Long.class,conversation))
                .isEqualTo(Long.parseLong((String)second.get("messageId")));
        fixture.call(HttpMethod.POST,base+"/read",12,new C2cChatController.Read((String)foreign.get("messageId"),"0"),400);
        fixture.call(HttpMethod.GET,base+"/messages?after="+foreign.get("messageId"),12,null,400);
        var latest=fixture.call(HttpMethod.GET,base+"/messages?after="+first.get("messageId"),12,null,200);
        assertThat(((Map<?,?>)latest.get("conversation")).get("unreadCount")).isEqualTo("0");
    }
    @Test void openingALongConversationLoadsRecentMessagesAndOlderPagingKeepsTheResumeCursor() throws Exception {
        UUID conversation=direct(12,11);
        for(int i=0;i<13;i++)send(11,conversation,UUID.randomUUID(),"消息 "+i,200);
        String base="/api/v1/c2c/conversations/"+conversation+"/messages";
        var page=fixture.call(HttpMethod.GET,base+"?latest=true&limit=5",12,null,200);
        var rows=(List<Map<String,Object>>)page.get("messages");
        assertThat(rows.stream().map(row->row.get("text"))).containsExactly("消息 8","消息 9","消息 10","消息 11","消息 12");
        assertThat(page.get("hasMore")).isEqualTo(true);
        var older=fixture.call(HttpMethod.GET,base+"?before="+rows.getFirst().get("messageId")+"&limit=5",12,null,200);
        assertThat(((List<Map<String,Object>>)older.get("messages")).stream().map(row->row.get("text")))
                .containsExactly("消息 3","消息 4","消息 5","消息 6","消息 7");
        assertThat((List<?>)fixture.call(HttpMethod.GET,base+"?after="+rows.getLast().get("messageId"),12,null,200).get("messages")).isEmpty();
        fixture.call(HttpMethod.GET,base+"?latest=true&after=1",12,null,400);
    }
    @Test void originalMessagesCannotBeEditedOrDeletedAndSearchEscapesWildcards() throws Exception {
        UUID conversation=direct(12,11);send(12,conversation,UUID.randomUUID(),"原始证据",200);
        assertThatThrownBy(()->fixture.db.jdbc.update("UPDATE c2c_messages SET text='changed' WHERE conversation_id=?",conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThatThrownBy(()->fixture.db.jdbc.update("DELETE FROM c2c_messages WHERE conversation_id=?",conversation))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat((List<?>)fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations?search=%25",12,null,200).get("items")).isEmpty();
    }
    @Test void latestHistoryIncludesTheLargestSignedInt64MessageIdentity() throws Exception {
        UUID conversation=direct(12,11);
        fixture.db.jdbc.update("INSERT INTO c2c_messages(message_id,conversation_id,sender_user_id,request_id,text) VALUES(?,?,?,?,?)",
                Long.MAX_VALUE,conversation,11,UUID.randomUUID(),"最大消息标识");
        var page=fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+conversation+"/messages?latest=true",12,null,200);
        assertThat(((List<Map<String,Object>>)page.get("messages")).getFirst().get("messageId")).isEqualTo("9223372036854775807");
    }
    @Test void adminRecentHistoryAndOwnSupportReceiptAreAuditedWithoutSendingAgain() throws Exception {
        UUID order=order(),conversation=orderConversation(order,12),request=UUID.randomUUID();
        for(int i=0;i<6;i++)send(12,conversation,UUID.randomUUID(),"订单消息 "+i,200);
        String path="/api/v1/admin/c2c/orders/"+order+"/chat";
        var page=fixture.call(HttpMethod.GET,path+"?latest=true&limit=2",9,null,200);
        assertThat(((List<Map<String,Object>>)page.get("messages")).stream().map(row->row.get("text"))).containsExactly("订单消息 4","订单消息 5");
        fixture.call(HttpMethod.GET,path+"?latest=true&after=1",9,null,400);
        assertThat(fixture.call(HttpMethod.GET,path+"/requests/"+request,9,null,200).get("status")).isEqualTo("UNKNOWN");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(6);
        var reply=fixture.call(HttpMethod.POST,path,9,new C2cChatController.Send(request,"客服回复",null),200);
        var recovered=fixture.call(HttpMethod.GET,path+"/requests/"+request,9,null,200);
        assertThat(recovered.get("status")).isEqualTo("SENT");assertThat(((Map<?,?>)recovered.get("message")).get("messageId")).isEqualTo(reply.get("messageId"));
        assertThat(fixture.call(HttpMethod.GET,path+"/requests/"+request,10,null,200).get("status")).isEqualTo("UNKNOWN");
        fixture.call(HttpMethod.GET,path+"/requests/"+request,12,null,403);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs",Integer.class)).isEqualTo(5);
        fixture.db.jdbc.execute("ALTER TABLE gateway_admin_operation_logs ADD CONSTRAINT fail_receipt_audit CHECK(false) NOT VALID");
        fixture.call(HttpMethod.GET,path+"/requests/"+request,9,null,503);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(7);
    }
    @Test void supportCanStartAnOrderConversationWithoutBecomingAParticipantOrCreatingFundIntents() throws Exception {
        UUID order=order();String path="/api/v1/admin/c2c/orders/"+order+"/chat";
        int funds=fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class);
        assertThat((List<?>)fixture.call(HttpMethod.GET,path+"?latest=true",9,null,200).get("messages")).isEmpty();
        fixture.call(HttpMethod.POST,path,9,new C2cChatController.Send(UUID.randomUUID(),"客服主动核实订单",null),200);
        var conversation=fixture.db.jdbc.queryForMap("SELECT * FROM c2c_conversations WHERE order_id=?",order);
        assertThat(conversation.get("low_user_id")).isEqualTo(11L);assertThat(conversation.get("high_user_id")).isEqualTo(12L);
        var id=conversation.get("conversation_id");
        var history=fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+id+"/messages?latest=true",12,null,200);
        assertThat(((Map<?,?>)((List<?>)history.get("messages")).getFirst()).get("authorKind")).isEqualTo("SUPPORT");
        fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+id+"/messages",9,null,403);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_fund_intents",Integer.class)).isEqualTo(funds);
    }
    @Test void approvedSupportReplyCanBeRecoveredAfterApprovalConsumptionWithoutAnotherWrite() throws Exception {
        fixture.properties.getSecurity().setRequireApprovalForHighRiskAdminWrites(true);
        UUID order=order();String path="/api/v1/admin/c2c/orders/"+order+"/chat";
        var body=new C2cChatController.Send(UUID.randomUUID(),"经审批的客服回复",null);
        fixture.call(HttpMethod.POST,path,9,body,403);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_conversations",Integer.class)).isZero();
        var approvals=fixture.bean(com.surprising.gateway.provider.auth.AdminApprovalService.class);
        var request=new com.surprising.gateway.provider.auth.AuthModels.AdminApprovalCreateRequest("c2c","POST",path,null,
                C2cAdminAccess.hash(fixture.json.writeValueAsBytes(body)),"Synthetic support reply approval");
        long approval=approvals.create("Bearer qa-9",request).approvalId();approvals.approve("Bearer qa-10",approval,"Synthetic independent review");
        var headers=Map.of("X-Admin-Approval-Id",Long.toString(approval));
        assertThat(fixture.perform(HttpMethod.POST,path,9,new C2cChatController.Send(body.requestId(),"不同正文",null),headers).getResponse().getStatus()).isEqualTo(403);
        assertThat(fixture.perform(HttpMethod.POST,path,9,body,headers).getResponse().getStatus()).isEqualTo(200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM gateway_admin_approval_requests WHERE approval_id=?",String.class,approval)).isEqualTo("CONSUMED");
        assertThat(fixture.call(HttpMethod.GET,path+"/requests/"+body.requestId(),9,null,200).get("status")).isEqualTo("SENT");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(1);
    }
    @Test void supportMessagesRequireSensitivePermissionAndAuditAndAreMarkedByServer() throws Exception {
        UUID order=order(),conversation=orderConversation(order,12);send(12,conversation,UUID.randomUUID(),"订单证据",200);
        String path="/api/v1/admin/c2c/orders/"+order+"/chat";
        fixture.call(HttpMethod.GET,path,12,null,403);
        fixture.call(HttpMethod.GET,path,9,null,200);
        var support=fixture.call(HttpMethod.POST,path,9,new C2cChatController.Send(UUID.randomUUID(),"客服核实中",null),200);
        assertThat(support.get("authorKind")).isEqualTo("SUPPORT");assertThat(support.get("senderUserId")).isEqualTo("9");
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM gateway_admin_operation_logs",Integer.class)).isEqualTo(2);
        fixture.db.jdbc.execute("ALTER TABLE gateway_admin_operation_logs ADD CONSTRAINT fail_chat_audit CHECK(false) NOT VALID");
        fixture.call(HttpMethod.GET,path,9,null,503);
        fixture.call(HttpMethod.POST,path,9,new C2cChatController.Send(UUID.randomUUID(),"不应保存的回复",null),503);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Integer.class)).isEqualTo(2);
    }
    @Test void endpointsRejectMissingIdentityInvalidContentAndClientAssignedSystemRole() throws Exception {
        UUID conversation=direct(12,11);
        fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations",0,null,401);
        send(12,conversation,null,"缺少请求号",400);send(12,conversation,UUID.randomUUID(),"x".repeat(4001),400);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+conversation+"/messages",12,
                Map.of("requestId",UUID.randomUUID().toString(),"text","普通用户","authorKind","SUPPORT","senderUserId","9"),200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT author_kind FROM c2c_messages",String.class)).isEqualTo("USER");
        assertThat(fixture.db.jdbc.queryForObject("SELECT sender_user_id FROM c2c_messages",Long.class)).isEqualTo(12);
    }
}
