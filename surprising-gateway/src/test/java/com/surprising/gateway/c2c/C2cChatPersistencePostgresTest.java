package com.surprising.gateway.c2c;

import com.surprising.gateway.c2c.controller.C2cChatController;
import com.surprising.gateway.c2c.repository.C2cOrdersRepository;
import com.surprising.gateway.c2c.service.C2cAttachments;
import com.surprising.gateway.c2c.service.C2cConversations;
import com.surprising.gateway.c2c.service.C2cMessages;
import com.surprising.gateway.c2c.service.C2cPaymentAccounts;
import com.surprising.gateway.c2c.storage.C2cR2Storage;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.verifyNoInteractions;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

@EnabledIfEnvironmentVariable(named="INSTRUMENT_TEST_JDBC_URL",matches=".+")
class C2cChatPersistencePostgresTest {
    private C2cHttpFixture fixture;
    @BeforeEach void setup() throws Exception {fixture=new C2cHttpFixture();fixture.db.jdbc.update("UPDATE c2c_profiles SET receive_strangers=true");}
    @AfterEach void cleanup() {if(fixture!=null) fixture.close();}

    @Test void inboxPagesUseOneQueryAndCountOnlyIncomingUnreadMessages() throws Exception {
        UUID first=direct(11);direct(13);
        var incoming=fixture.bean(C2cMessages.class).send(11,first,UUID.randomUUID(),"Incoming",null);
        fixture.bean(C2cMessages.class).send(12,first,UUID.randomUUID(),"Own reply",null);
        var jdbc=new CountingJdbc(fixture.db.jdbc.getDataSource());var conversations=C2cDatabaseFixture.conversations(jdbc,new C2cOrdersRepository(jdbc));
        var page=conversations.list(12,"",10,0);assertThat(jdbc.selects).isEqualTo(1);
        assertThat((List<?>)page.get("items")).hasSize(2);
        var view=((List<?>)page.get("items")).stream().map(value -> (Map<?,?>)value).filter(value -> first.toString().equals(value.get("conversationId"))).findFirst().orElseThrow();
        assertThat(view.get("unreadCount")).isEqualTo("1");assertThat(((Map<?,?>)view.get("lastMessage")).get("text")).isEqualTo("Own reply");
        fixture.bean(C2cMessages.class).markRead(12,first,incoming.messageId(),0);
        assertThat(conversations.view(12,fixture.bean(C2cConversations.class).get(12,first,false)).get("unreadCount")).isEqualTo("0");
        fixture.db.jdbc.update("UPDATE c2c_profiles SET nickname='QA %_Maker' WHERE user_id=11");
        jdbc.selects=0;assertThat((List<?>)conversations.list(12,"%_",10,0).get("items")).hasSize(1);assertThat(jdbc.selects).isEqualTo(1);
        var one=conversations.list(12,"",1,0);var two=conversations.list(12,"",1,1);
        assertThat(one.get("hasMore")).isEqualTo(true);assertThat(two.get("hasMore")).isEqualTo(false);
        assertThat(((Map<?,?>)((List<?>)one.get("items")).getFirst()).get("conversationId"))
                .isNotEqualTo(((Map<?,?>)((List<?>)two.get("items")).getFirst()).get("conversationId"));
        jdbc.selects=0;assertThat((List<?>)conversations.list(9,"",10,0).get("items")).isEmpty();assertThat(jdbc.selects).isEqualTo(1);
        verifyNoInteractions(fixture.core);
    }

    @Test void messagePagesBatchAttachmentsAndSerializeLargeNativeCursorsAsStrings() throws Exception {
        UUID id=direct(11);byte[] bytes=C2cAttachmentsPostgresTest.png();
        fixture.db.jdbc.queryForObject("SELECT setval(pg_get_serial_sequence('c2c_messages','message_id'),9007199254740993,false)",Long.class);
        for(int i=0;i<3;i++) {
            UUID file=UUID.randomUUID();fixture.bean(C2cAttachments.class).upload(11,file,"MESSAGE",null,id,"qa-query.png","image/png",bytes);
            fixture.bean(C2cMessages.class).send(11,id,UUID.randomUUID(),"With attachment "+i,file);
        }
        var jdbc=new CountingJdbc(fixture.db.jdbc.getDataSource());var conversations=C2cDatabaseFixture.conversations(jdbc,new C2cOrdersRepository(jdbc));
        var attachments=C2cDatabaseFixture.attachments(jdbc,conversations,new C2cOrdersRepository(jdbc),fixture.bean(C2cPaymentAccounts.class),
                new C2cR2Storage(fixture.r2),fixture.db.transactions.getTransactionManager());
        var messages=C2cDatabaseFixture.messages(jdbc,conversations,attachments,fixture.json);
        var page=messages.page(12,id,0,null,0,2);assertThat(jdbc.selects).isEqualTo(6);
        assertThat(page.messages()).hasSize(2);assertThat(page.hasMore()).isTrue();
        assertThat(page.messages().getFirst().messageId()).isEqualTo(9007199254740993L);
        assertThat(page.messages()).allSatisfy(message -> assertThat(message.attachment()).containsEntry("status","READY").doesNotContainKeys("storageKey","key","sha256"));
        String serialized=fixture.json.writeValueAsString(page);
        assertThat(serialized).contains("\"messageId\":\"9007199254740993\"").doesNotContain("c2c/11/","storageKey");
        var next=messages.page(12,id,page.messages().getLast().messageId(),null,0,2);
        assertThat(next.messages()).singleElement().satisfies(message -> assertThat(message.messageId()).isEqualTo(9007199254740995L));
        assertThat(next.hasMore()).isFalse();verifyNoInteractions(fixture.core);
    }

    @Test void failedParticipantReadInsertDoesNotLeaveAnOrphanConversation() throws Exception {
        fixture.db.jdbc.execute("ALTER TABLE c2c_message_reads ADD CONSTRAINT reject_peer_read CHECK(user_id<>11)");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,new C2cChatController.Start(null,11L),409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_conversations",Long.class)).isZero();
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_message_reads",Long.class)).isZero();
    }

    @Test void failedConversationTouchRollsBackMessageAndOriginalRequestCanRetry() throws Exception {
        UUID id=direct(11),request=UUID.randomUUID();
        fixture.db.jdbc.execute("""
                CREATE FUNCTION reject_chat_touch() RETURNS trigger LANGUAGE plpgsql AS $$
                BEGIN RAISE EXCEPTION 'Synthetic chat touch failure' USING ERRCODE='23514'; END $$
                """);
        fixture.db.jdbc.execute("CREATE TRIGGER reject_chat_touch BEFORE UPDATE ON c2c_conversations FOR EACH ROW EXECUTE FUNCTION reject_chat_touch()");
        var body=new C2cChatController.Send(request,"Atomic chat message",null);
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+id+"/messages",12,body,409);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Long.class)).isZero();
        assertThat(fixture.call(HttpMethod.GET,"/api/v1/c2c/conversations/"+id+"/requests/"+request,12,null,200).get("status")).isEqualTo("UNKNOWN");
        fixture.db.jdbc.execute("DROP TRIGGER reject_chat_touch ON c2c_conversations");
        fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations/"+id+"/messages",12,body,200);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_messages",Long.class)).isEqualTo(1);
    }

    @Test void storedFileKeepsItsCommittedAdmissionIfReadyUpdateFails() throws Exception {
        UUID id=UUID.randomUUID();byte[] bytes=C2cAttachmentsPostgresTest.png();var attachments=fixture.bean(C2cAttachments.class);
        fixture.db.jdbc.execute("ALTER TABLE c2c_attachments ADD CONSTRAINT reject_ready CHECK(status<>'READY')");
        assertThatThrownBy(()->attachments.upload(12,id,"PAYMENT_QR",null,null,"qa-ready.png","image/png",bytes))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        assertThat(fixture.db.jdbc.queryForObject("SELECT count(*) FROM c2c_attachments WHERE attachment_id=?",Long.class,id)).isEqualTo(1);
        assertThat(fixture.db.jdbc.queryForObject("SELECT status FROM c2c_attachments WHERE attachment_id=?",String.class,id)).isEqualTo("UPLOADING");
        var storage=new C2cR2Storage(fixture.r2);assertThat(storage.read("c2c/12/"+id)).isEqualTo(bytes);
        byte[] changed=bytes.clone();changed[changed.length-1]^=1;
        assertThatThrownBy(()->attachments.upload(12,id,"PAYMENT_QR",null,null,"qa-ready.png","image/png",changed)).isInstanceOf(IllegalStateException.class);
        assertThat(storage.read("c2c/12/"+id)).isEqualTo(bytes);
        fixture.db.jdbc.execute("ALTER TABLE c2c_attachments DROP CONSTRAINT reject_ready");
        assertThat(attachments.confirm(12,id).get("status")).isEqualTo("READY");verifyNoInteractions(fixture.core);
    }
    private UUID direct(long target) throws Exception {
        return UUID.fromString((String)fixture.call(HttpMethod.POST,"/api/v1/c2c/conversations",12,new C2cChatController.Start(null,target),200).get("conversationId"));
    }
    private static final class CountingJdbc extends JdbcTemplate {
        private int selects;
        CountingJdbc(DataSource source) {super(source);}
        @Override public <T> List<T> query(String sql,RowMapper<T> mapper,Object... args) {selects++;return super.query(sql,mapper,args);}
    }
}
